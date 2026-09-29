package com.example.popping.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.popping.config.app.CacheConfig;
import com.example.popping.controller.mvc.BoardController;
import com.example.popping.controller.mvc.PostController;
import com.example.popping.domain.Board;
import com.example.popping.domain.Post;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.exception.MvcExceptionHandler;
import com.example.popping.repository.LikeRepository;
import com.example.popping.repository.PostRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Caffeine, service, MVC controllers/advice; persistence and transaction callbacks are mocked. */
class PostCacheErrorMappingTest {
    private static final long POST_ID = 987654321L;
    private static final long BOARD_ID = 42L;
    private static final String SLUG = "test";
    enum DetailRoute { CACHED, CACHE_ABSENT }
    enum BoardRoute { CACHED, CACHE_ABSENT, SECOND_PAGE }
    private final PostRepository posts = mock(PostRepository.class);
    private final BoardService boards = mock(BoardService.class);
    private CacheManager cacheManager;
    private PostService service;

    private MockMvc mvc(boolean cacheAbsent) {
        SimpleCacheManager manager = cacheAbsent ? new SimpleCacheManager()
                : (SimpleCacheManager) new CacheConfig().cacheManager(true, 5, true);
        manager.initializeCaches();
        cacheManager = manager;
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        service = new PostService(boards, mock(ImageService.class), mock(UserService.class),
                mock(ViewCountService.class), mock(PasswordEncoder.class), posts,
                mock(LikeRepository.class), manager, tx, mock(ApplicationEventPublisher.class));
        return MockMvcBuilders.standaloneSetup(new PostController(service), new BoardController(boards, service))
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
                .setControllerAdvice(new MvcExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    private MockHttpServletRequestBuilder detailRequest() {
        return get("/boards/{slug}/{postId}", SLUG, POST_ID);
    }

    private MockHttpServletRequestBuilder boardRequest(BoardRoute route) {
        return get("/boards/{slug}", SLUG).param("page", route == BoardRoute.SECOND_PAGE ? "1" : "0");
    }

    private void assertError(MvcResult result, ErrorType type, String message) {
        assertNotNull(result.getModelAndView());
        var model = result.getModelAndView().getModel();
        System.out.println("POST_CACHE_ERROR status=" + result.getResponse().getStatus()
                + " view=" + result.getModelAndView().getViewName() + " code=" + model.get("errorCode"));
        assertEquals("error/custom-error", result.getModelAndView().getViewName());
        assertEquals(type.name(), model.get("errorCode"));
        assertEquals(message, model.get("message"));
        // MVC advice currently does not set an HTTP error status. This is a separate known limitation.
        assertEquals(200, result.getResponse().getStatus());
    }

    @ParameterizedTest
    @EnumSource(DetailRoute.class)
    void missingPost_preservesDomainErrorModel(DetailRoute route) throws Exception {
        when(posts.findById(POST_ID)).thenReturn(Optional.empty());
        var result = mvc(route == DetailRoute.CACHE_ABSENT).perform(detailRequest()).andReturn();
        assertError(result, ErrorType.POST_NOT_FOUND, "해당 게시글이 존재하지 않습니다: " + POST_ID);
    }

    @ParameterizedTest
    @EnumSource(DetailRoute.class)
    void detailStorageFailure_remainsInternalWithoutLeakingDetails(DetailRoute route) throws Exception {
        when(posts.findById(POST_ID)).thenThrow(new DataAccessResourceFailureException("internal-db-secret"));
        var result = mvc(route == DetailRoute.CACHE_ABSENT).perform(detailRequest()).andReturn();
        assertError(result, ErrorType.INTERNAL_ERROR, ErrorType.INTERNAL_ERROR.getMessage());
    }

    @ParameterizedTest
    @EnumSource(BoardRoute.class)
    void missingBoard_isCheckedBeforeCacheLoader(BoardRoute route) throws Exception {
        when(boards.getBoard(SLUG)).thenThrow(new CustomAppException(ErrorType.BOARD_NOT_FOUND));
        var result = mvc(route == BoardRoute.CACHE_ABSENT).perform(boardRequest(route)).andReturn();
        assertError(result, ErrorType.BOARD_NOT_FOUND, ErrorType.BOARD_NOT_FOUND.getMessage());
        verifyNoInteractions(posts);
    }

    private Board existingBoard() {
        Board board = mock(Board.class);
        when(board.getId()).thenReturn(BOARD_ID);
        when(boards.getBoard(SLUG)).thenReturn(board);
        return board;
    }

    @ParameterizedTest
    @EnumSource(BoardRoute.class)
    void boardStorageFailure_remainsInternalWithoutLeakingDetails(BoardRoute route) throws Exception {
        Board board = existingBoard();
        when(posts.findPostListByBoard(eq(board), any())).thenThrow(new DataAccessResourceFailureException("internal-db-secret"));
        var result = mvc(route == BoardRoute.CACHE_ABSENT).perform(boardRequest(route)).andReturn();
        assertError(result, ErrorType.INTERNAL_ERROR, ErrorType.INTERNAL_ERROR.getMessage());
    }

    @Test
    void detailUnknownArgumentFailure_isNotReclassifiedAsValidationError() throws Exception {
        when(posts.findById(POST_ID)).thenThrow(new IllegalArgumentException("internal-loader-detail"));
        assertError(mvc(false).perform(detailRequest()).andReturn(), ErrorType.INTERNAL_ERROR,
                ErrorType.INTERNAL_ERROR.getMessage());
    }

    @Test
    void detailUnrelatedWrapperWithDomainCause_isNotRecursivelyUnwrapped() throws Exception {
        when(posts.findById(POST_ID)).thenThrow(new IllegalStateException("unexpected wrapper",
                new CustomAppException(ErrorType.POST_NOT_FOUND)));
        assertError(mvc(false).perform(detailRequest()).andReturn(), ErrorType.INTERNAL_ERROR,
                ErrorType.INTERNAL_ERROR.getMessage());
    }

    @Test
    void detailPreservesOriginalDomainExceptionInstance() {
        CustomAppException original = new CustomAppException(ErrorType.ACCESS_DENIED, "expected domain detail");
        when(posts.findById(POST_ID)).thenThrow(original);
        mvc(false);
        assertSame(original, assertThrows(CustomAppException.class,
                () -> service.getPostResponse(POST_ID, null, null)));
    }

    @Test
    void detailFailedLoad_isNotCachedAndSubsequentSuccessIsCached() throws Exception {
        Board board = existingBoard();
        Post post = mock(Post.class);
        when(post.getId()).thenReturn(POST_ID);
        when(post.isGuest()).thenReturn(true);
        when(post.getBoard()).thenReturn(board);
        when(posts.findById(POST_ID)).thenReturn(Optional.empty(), Optional.of(post));
        MockMvc mvc = mvc(false);
        assertError(mvc.perform(detailRequest()).andReturn(), ErrorType.POST_NOT_FOUND,
                "해당 게시글이 존재하지 않습니다: " + POST_ID);
        assertNull(cacheManager.getCache(CacheConfig.POST_DETAIL_CACHE).get(POST_ID));
        mvc.perform(detailRequest()).andExpect(view().name("post/detail")).andExpect(model().attributeExists("post"));
        mvc.perform(detailRequest()).andExpect(view().name("post/detail"));
        verify(posts, times(2)).findById(POST_ID);
    }

    @Test
    void boardFailedLoad_isNotCachedAndSubsequentSuccessIsCached() throws Exception {
        Board board = existingBoard();
        when(posts.findPostListByBoard(eq(board), any()))
                .thenThrow(new DataAccessResourceFailureException("temporary-db-failure"))
                .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));
        MockMvc mvc = mvc(false);
        assertError(mvc.perform(boardRequest(BoardRoute.CACHED)).andReturn(), ErrorType.INTERNAL_ERROR,
                ErrorType.INTERNAL_ERROR.getMessage());
        assertNull(cacheManager.getCache(CacheConfig.BOARD_FIRST_PAGE_CACHE).get(BOARD_ID));
        mvc.perform(boardRequest(BoardRoute.CACHED)).andExpect(view().name("board/detail"))
                .andExpect(model().attributeExists("postPage"));
        mvc.perform(boardRequest(BoardRoute.CACHED)).andExpect(view().name("board/detail"));
        verify(posts, times(2)).findPostListByBoard(eq(board), any());
    }
}
