package com.example.popping.service;

import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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

/** Actual MVC binding/advice, filter, Caffeine and service; repository and transaction callbacks mocked. */
class CommentPageBoundaryTest {
    private final PostService posts = mock(PostService.class);
    private final CommentRepository comments = mock(CommentRepository.class);
    private MockMvc mvc;
    private SimpleCacheManager caches;

    @BeforeEach
    void setup() {
        Post post = mock(Post.class);
        when(posts.getPost(7L)).thenReturn(post);
        when(comments.findPagedCommentTree(eq(7L), eq(CommentService.COMMENTS_SIZE), anyInt()))
                .thenAnswer(invocation -> {
                    System.out.println("PAGINATION_REPOSITORY_OFFSET=" + invocation.getArgument(2));
                    return List.of();
                });
        caches = (SimpleCacheManager) new CacheConfig().cacheManager(true, 5, true);
        caches.initializeCaches();
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        CommentService service = new CommentService(posts, mock(UserService.class), comments,
                mock(LikeRepository.class), mock(PasswordEncoder.class), caches, tx,
                mock(ApplicationEventPublisher.class), mock(GuestIdentifierService.class));
        mvc = MockMvcBuilders.standaloneSetup(new CommentController(service))
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new StickyPrimaryFilter()).build();
    }

    @AfterEach
    void clearSticky() {
        StickyPrimaryHolder.clear();
    }

    private void assertInvalidBeforeStorage(MvcResult result) throws Exception {
        System.out.println("PAGINATION_STATUS=" + result.getResponse().getStatus()
                + " body=" + result.getResponse().getContentAsString());
        assertEquals(400, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("\"errorCode\":\"VALIDATION_ERROR\""));
        verifyNoInteractions(posts, comments);
        assertNull(caches.getCache(CacheConfig.COMMENT_FIRST_PAGE_CACHE).get(7L));
        assertFalse(StickyPrimaryHolder.isSticky());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE, 21474837, Integer.MAX_VALUE})
    void invalidIntegerPage_isRejectedBeforeCacheAndStorage(int page) throws Exception {
        assertInvalidBeforeStorage(mvc.perform(get("/boards/test/7/comments")
                .param("page", Integer.toString(page))).andReturn());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "2147483648", "<script>private-input</script>"})
    void malformedPage_isSafeValidationError(String page) throws Exception {
        var result = mvc.perform(get("/boards/test/7/comments").param("page", page)).andReturn();
        assertInvalidBeforeStorage(result);
        assertTrue(result.getResponse().getContentAsString().contains(ErrorType.VALIDATION_ERROR.getMessage()));
        assertFalse(result.getResponse().getContentAsString().contains(page));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "21474836", ""})
    void representableOffset_preservesExistingPageBehavior(String rawPage) throws Exception {
        int page = rawPage.isEmpty() ? 0 : Integer.parseInt(rawPage);
        mvc.perform(get("/boards/test/7/comments").param("page", rawPage))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentPage").value(page));
        verify(comments).findPagedCommentTree(7L, CommentService.COMMENTS_SIZE,
                Math.toIntExact((long) page * CommentService.COMMENTS_SIZE));
    }

    @Test
    void stickyRequest_doesNotBypassPageValidation() throws Exception {
        assertInvalidBeforeStorage(mvc.perform(get("/boards/test/7/comments").param("page", "-1")
                .cookie(new Cookie("STICKY_PRIMARY", "1"))).andReturn());
    }

    @Test
    void malformedPathIdentifier_usesSameSafeBindingErrorContract() throws Exception {
        assertInvalidBeforeStorage(mvc.perform(get("/boards/test/not-a-number/comments")).andReturn());
    }

    @Test
    void validPageStorageFailure_remainsInternalError() throws Exception {
        when(posts.getPost(7L)).thenThrow(new DataAccessResourceFailureException("private-db-details"));
        mvc.perform(get("/boards/test/7/comments").param("page", "1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(ErrorType.INTERNAL_ERROR.getMessage()));
    }

    @Test
    void validPageMissingPost_remainsNotFound() throws Exception {
        when(posts.getPost(7L)).thenThrow(new CustomAppException(ErrorType.POST_NOT_FOUND));
        mvc.perform(get("/boards/test/7/comments").param("page", "1"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("POST_NOT_FOUND"));
    }
}
