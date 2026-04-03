package com.example.popping.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommentCacheEvictListenerTest {

    @Mock CacheManager cacheManager;
    @Mock Cache cache;

    @InjectMocks CommentCacheEvictListener listener;

    @Test
    @DisplayName("이벤트 수신: 해당 postId의 캐시를 evict한다")
    void onCommentChange_evictsPostIdFromCache() {
        // given
        when(cacheManager.getCache("commentFirstPage")).thenReturn(cache);

        // when
        listener.onCommentChange(new CommentCacheEvictEvent(10L));

        // then
        verify(cache).evict(10L);
    }

    @Test
    @DisplayName("이벤트 수신: 캐시가 null이면 evict를 시도하지 않는다")
    void onCommentChange_cacheNull_doesNothing() {
        // given
        when(cacheManager.getCache("commentFirstPage")).thenReturn(null);

        // when & then
        assertDoesNotThrow(() -> listener.onCommentChange(new CommentCacheEvictEvent(10L)));
        verify(cache, never()).evict(any());
    }

    @Test
    @DisplayName("이벤트 수신: postId가 null이면 evict를 시도하지 않는다")
    void onCommentChange_postIdNull_doesNothing() {
        // given
        when(cacheManager.getCache("commentFirstPage")).thenReturn(cache);

        // when & then
        assertDoesNotThrow(() -> listener.onCommentChange(new CommentCacheEvictEvent(null)));
        verify(cache, never()).evict(any());
    }
}
