package com.example.popping.service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.popping.config.app.CacheConfig;
import com.example.popping.domain.Board;
import com.example.popping.domain.Post;
import com.example.popping.dto.PostResponse;
import com.example.popping.cache.NoOpCacheInvalidationBroadcaster;
import com.example.popping.event.CacheEvictEvent;
import com.example.popping.event.CacheEvictListener;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.LikeRepository;
import com.example.popping.repository.PostRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Two local cache managers, one mock data source. Real JVM/HTTP/DB coverage lives in the isolated rig. */
class PostDetailFreshnessTest {
    private final PostRepository repository = mock(PostRepository.class);
    private final AtomicReference<Post> current = new AtomicReference<>();
    private final SimpleCacheManager[] managers = new SimpleCacheManager[2];

    private Post post(String title, String content, int count) {
        Post post = mock(Post.class);
        when(post.getId()).thenReturn(1L);
        when(post.isGuest()).thenReturn(true);
        when(post.getBoard()).thenReturn(mock(Board.class));
        when(post.getTitle()).thenReturn(title);
        when(post.getContent()).thenReturn(content);
        when(post.getCommentCount()).thenReturn(count);
        return post;
    }

    private PostService[] services(boolean enabled) {
        when(repository.findById(1L)).thenAnswer(call -> Optional.ofNullable(current.get()));
        PostService[] services = new PostService[2];
        for (int i = 0; i < 2; i++) {
            managers[i] = (SimpleCacheManager) new CacheConfig().cacheManager(true, 5, enabled);
            managers[i].initializeCaches();
            TransactionTemplate tx = mock(TransactionTemplate.class);
            when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
            services[i] = new PostService(mock(BoardService.class), mock(ImageService.class),
                    mock(UserService.class), mock(ViewCountService.class), mock(PasswordEncoder.class),
                    repository, mock(LikeRepository.class), managers[i], tx, mock(ApplicationEventPublisher.class));
        }
        current.set(post("old", "old content", 0));
        for (PostService service : services) {
            assertEquals("old", service.getPostResponse(1L, null, null).title());
        }
        return services;
    }

    private void invalidateWriter() {
        new CacheEvictListener(managers[1], new NoOpCacheInvalidationBroadcaster()).onCacheEvict(new CacheEvictEvent(CacheConfig.POST_DETAIL_CACHE, 1L));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updateIsFreshOnBothNodesOnlyWithoutLocalDetailCache(boolean enabled) {
        PostService[] services = services(enabled);
        current.set(post("new", "new content", 0));
        invalidateWriter();
        PostResponse reader = services[0].getPostResponse(1L, null, null);
        PostResponse writer = services[1].getPostResponse(1L, null, null);
        assertEquals(enabled ? "old" : "new", reader.title());
        assertEquals(enabled ? "old content" : "new content", reader.content());
        assertEquals("new", writer.title());
        verify(repository, times(enabled ? 3 : 4)).findById(1L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commentCountDoesNotRequireRemoteInvalidationWhenDisabled(boolean enabled) {
        PostService[] services = services(enabled);
        current.set(post("old", "old content", 1));
        invalidateWriter();
        assertEquals(enabled ? 0 : 1, services[0].getPostResponse(1L, null, null).commentCount());
        assertEquals(1, services[1].getPostResponse(1L, null, null).commentCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletedPostDoesNotSurviveInOtherNodesWhenDisabled(boolean enabled) {
        PostService[] services = services(enabled);
        current.set(null);
        invalidateWriter();
        if (enabled) {
            assertEquals("old", services[0].getPostResponse(1L, null, null).title());
        } else {
            assertEquals(ErrorType.POST_NOT_FOUND, assertThrows(CustomAppException.class,
                    () -> services[0].getPostResponse(1L, null, null)).getErrorType());
        }
        assertEquals(ErrorType.POST_NOT_FOUND, assertThrows(CustomAppException.class,
                () -> services[1].getPostResponse(1L, null, null)).getErrorType());
    }
}
