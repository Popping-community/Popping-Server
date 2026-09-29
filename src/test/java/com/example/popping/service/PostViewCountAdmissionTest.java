package com.example.popping.service;

import java.util.Optional;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.popping.config.app.CacheConfig;
import com.example.popping.domain.Board;
import com.example.popping.domain.Post;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.LikeRepository;
import com.example.popping.repository.PostRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PostViewCountAdmissionTest {
    private static final long POST_ID = 501L;

    private final PostRepository repository = mock(PostRepository.class);
    private ViewCountService views;
    private PostService posts;

    private void setUp(boolean cacheEnabled) {
        SimpleCacheManager manager = (SimpleCacheManager) new CacheConfig().cacheManager(true, 5, cacheEnabled);
        manager.initializeCaches();
        TransactionTemplate readOnlyTx = mock(TransactionTemplate.class);
        when(readOnlyTx.execute(any())).thenAnswer(call ->
                ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        views = new ViewCountService(repository, mock(TransactionTemplate.class), manager,
                new SimpleMeterRegistry());
        posts = new PostService(mock(BoardService.class), mock(ImageService.class),
                mock(UserService.class), views, mock(PasswordEncoder.class), repository,
                mock(LikeRepository.class), manager, readOnlyTx, mock(ApplicationEventPublisher.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingPostDoesNotQueueView(boolean cacheEnabled) {
        setUp(cacheEnabled);
        when(repository.findById(POST_ID)).thenReturn(Optional.empty());

        CustomAppException error = assertThrows(CustomAppException.class,
                () -> posts.getPostResponse(POST_ID, null, null));

        assertEquals(ErrorType.POST_NOT_FOUND, error.getErrorType());
        assertEquals(0L, views.getPendingCount(POST_ID));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulReadQueuesOneViewWithoutExtraPostLookup(boolean cacheEnabled) {
        setUp(cacheEnabled);
        Board board = mock(Board.class);
        Post post = mock(Post.class);
        when(post.getId()).thenReturn(POST_ID);
        when(post.isGuest()).thenReturn(true);
        when(post.getBoard()).thenReturn(board);
        when(post.getViewCount()).thenReturn(5L);
        when(repository.findById(POST_ID)).thenReturn(Optional.of(post));

        var response = posts.getPostResponse(POST_ID, null, null);

        assertEquals(6L, response.viewCount());
        assertEquals(1L, views.getPendingCount(POST_ID));
        verify(repository).findById(POST_ID);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedReadsAddOneViewEachWithoutDoubleCounting(boolean cacheEnabled) {
        setUp(cacheEnabled);
        Board board = mock(Board.class);
        Post post = mock(Post.class);
        when(post.getId()).thenReturn(POST_ID);
        when(post.isGuest()).thenReturn(true);
        when(post.getBoard()).thenReturn(board);
        when(post.getViewCount()).thenReturn(5L);
        when(repository.findById(POST_ID)).thenReturn(Optional.of(post));

        var first = posts.getPostResponse(POST_ID, null, null);
        var second = posts.getPostResponse(POST_ID, null, null);

        assertEquals(6L, first.viewCount());
        assertEquals(7L, second.viewCount());
        assertEquals(2L, views.getPendingCount(POST_ID));
        verify(repository, times(cacheEnabled ? 1 : 2)).findById(POST_ID);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedLookupDoesNotQueueView(boolean cacheEnabled) {
        setUp(cacheEnabled);
        when(repository.findById(POST_ID))
                .thenThrow(new DataAccessResourceFailureException("storage unavailable"));

        assertThrows(RuntimeException.class, () -> posts.getPostResponse(POST_ID, null, null));

        assertEquals(0L, views.getPendingCount(POST_ID));
    }
}
