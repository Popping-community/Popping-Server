package com.example.popping.service;

import java.util.List;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.popping.config.app.CacheConfig;
import com.example.popping.config.db.StickyPrimaryHolder;
import com.example.popping.controller.api.CommentController;
import com.example.popping.domain.Post;
import com.example.popping.exception.ApiExceptionHandler;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.filter.StickyPrimaryFilter;
import com.example.popping.repository.CommentRepository;
import com.example.popping.repository.LikeRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Caffeine, service, filter, controller and MVC advice; storage/transaction callbacks are mocked. */
class CommentCacheErrorMappingTest {
    private static final long POST_ID = 987654321L;
    enum Route { CACHED, STICKY, DISABLED, SECOND_PAGE }
    private final PostService posts = mock(PostService.class);
    private final CommentRepository comments = mock(CommentRepository.class);
    private CacheManager cacheManager;
    private CommentService service;

    @AfterEach
    void clearThreadContext() {
        StickyPrimaryHolder.clear();
    }

    private MockMvc mvc(Route route) {
        SimpleCacheManager manager = (SimpleCacheManager) new CacheConfig().cacheManager(route != Route.DISABLED, 5, true);
        manager.initializeCaches();
        cacheManager = manager;
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        service = new CommentService(posts, mock(UserService.class), comments, mock(LikeRepository.class),
                mock(PasswordEncoder.class), manager, tx, mock(ApplicationEventPublisher.class),
                mock(GuestIdentifierService.class));
        return MockMvcBuilders.standaloneSetup(new CommentController(service))
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new StickyPrimaryFilter()).build();
    }

    private MockHttpServletRequestBuilder request(Route route) {
        var request = get("/boards/test/{postId}/comments", POST_ID);
        if (route == Route.STICKY) request.cookie(new Cookie("STICKY_PRIMARY", "1"));
        if (route == Route.SECOND_PAGE) request.param("page", "1");
        return request;
    }

    private void observe(Route route, MvcResult result) throws Exception {
        System.out.println("ERROR_MAPPING route=" + route + " status=" + result.getResponse().getStatus()
                + " body=" + result.getResponse().getContentAsString());
        assertFalse(StickyPrimaryHolder.isSticky(), "Filter must clear context even after failure");
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void missingPost_hasSameNotFoundContract(Route route) throws Exception {
        when(posts.getPost(POST_ID)).thenThrow(new CustomAppException(ErrorType.POST_NOT_FOUND));
        MockMvc mvc = mvc(route);
        MvcResult result = mvc.perform(request(route)).andReturn();
        observe(route, result);
        assertEquals(404, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("\"errorCode\":\"POST_NOT_FOUND\""));
        assertTrue(result.getResponse().getContentAsString().contains(ErrorType.POST_NOT_FOUND.getMessage()));
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void storageFailure_remainsInternalErrorWithoutLeakingDetails(Route route) throws Exception {
        when(posts.getPost(POST_ID)).thenThrow(new DataAccessResourceFailureException("internal-db-endpoint-secret"));
        MockMvc mvc = mvc(route);
        MvcResult result = mvc.perform(request(route)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(ErrorType.INTERNAL_ERROR.getMessage())).andReturn();
        observe(route, result);
        assertFalse(result.getResponse().getContentAsString().contains("internal-db-endpoint-secret"));
    }

    @Test
    void unknownLoaderArgumentFailure_isNotConvertedToClientError() throws Exception {
        when(posts.getPost(POST_ID)).thenThrow(new IllegalArgumentException("internal-loader-detail"));
        mvc(Route.CACHED).perform(request(Route.CACHED)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(ErrorType.INTERNAL_ERROR.getMessage()));
    }

    @Test
    void unrelatedWrapperWithDomainCause_isNotRecursivelyUnwrapped() throws Exception {
        when(posts.getPost(POST_ID)).thenThrow(new IllegalStateException("unexpected wrapper",
                new CustomAppException(ErrorType.POST_NOT_FOUND)));
        mvc(Route.CACHED).perform(request(Route.CACHED)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"));
    }

    @Test
    void failedLoad_isNotCachedAndNextLoadCanSucceed() throws Exception {
        Post post = mock(Post.class);
        when(posts.getPost(POST_ID)).thenThrow(new CustomAppException(ErrorType.POST_NOT_FOUND)).thenReturn(post);
        when(comments.findPagedCommentTree(POST_ID, CommentService.COMMENTS_SIZE, 0)).thenReturn(List.of());
        MockMvc mvc = mvc(Route.CACHED);
        mvc.perform(request(Route.CACHED)).andExpect(status().isNotFound());
        assertNull(cacheManager.getCache(CacheConfig.COMMENT_FIRST_PAGE_CACHE).get(POST_ID));
        mvc.perform(request(Route.CACHED)).andExpect(status().isOk()).andExpect(jsonPath("$.totalComments").value(0));
        mvc.perform(request(Route.CACHED)).andExpect(status().isOk());
        verify(posts, times(2)).getPost(POST_ID);
        verify(comments, times(1)).findPagedCommentTree(POST_ID, CommentService.COMMENTS_SIZE, 0);
    }

    @Test
    void servicePreservesTheOriginalDomainExceptionInstance() {
        CustomAppException original = new CustomAppException(ErrorType.ACCESS_DENIED, "expected domain detail");
        when(posts.getPost(POST_ID)).thenThrow(original);
        mvc(Route.CACHED);
        assertSame(original, assertThrows(CustomAppException.class,
                () -> service.getCommentPage(POST_ID, 0, null, null)));
    }
}
