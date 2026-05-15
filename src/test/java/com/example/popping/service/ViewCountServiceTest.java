package com.example.popping.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.popping.config.app.CacheConfig;
import com.example.popping.repository.PostRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ViewCountServiceTest {

    @Mock
    PostRepository postRepository;

    @Mock
    TransactionTemplate txTemplate;

    @Mock
    CacheManager cacheManager;

    @InjectMocks
    ViewCountService viewCountService;

    @BeforeEach
    void setUp() {
        // Make txTemplate.executeWithoutResult actually run the callback
        doAnswer(inv -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> cb = inv.getArgument(0);
            cb.accept(null);
            return null;
        }).when(txTemplate).executeWithoutResult(any());

        viewCountService.flushViewCounts();
        clearInvocations(postRepository);
        clearInvocations(txTemplate);
    }

    @Test
    @DisplayName("increaseView: 메모리 카운터만 증가하고 DB를 호출하지 않는다")
    void increaseView_onlyMemory() {
        viewCountService.increaseView(1L);
        viewCountService.increaseView(1L);
        viewCountService.increaseView(1L);

        assertThat(viewCountService.getPendingCount(1L)).isEqualTo(3);
        verifyNoInteractions(postRepository);
    }

    @Test
    @DisplayName("getPendingCount: 존재하지 않는 postId는 0을 반환한다")
    void getPendingCount_nonExistent_returnsZero() {
        assertThat(viewCountService.getPendingCount(999L)).isEqualTo(0);
    }

    @Test
    @DisplayName("flushViewCounts: 누적된 조회수를 한번에 DB에 반영하고 메모리를 비운다")
    void flushViewCounts_writesToDb() {
        viewCountService.increaseView(1L);
        viewCountService.increaseView(1L);
        viewCountService.increaseView(2L);

        viewCountService.flushViewCounts();

        verify(postRepository).increaseViewCountBy(1L, 2L);
        verify(postRepository).increaseViewCountBy(2L, 1L);
        assertThat(viewCountService.getPendingCount(1L)).isEqualTo(0);
        assertThat(viewCountService.getPendingCount(2L)).isEqualTo(0);
    }

    @Test
    @DisplayName("flushViewCounts: pending이 없으면 DB를 호출하지 않는다")
    void flushViewCounts_empty_noDbCall() {
        viewCountService.flushViewCounts();

        verifyNoInteractions(postRepository);
        verifyNoInteractions(txTemplate);
    }

    @Test
    @DisplayName("flushViewCounts: flush 후 다시 조회수를 쌓으면 새로운 카운터로 동작한다")
    void flushViewCounts_thenIncrementAgain() {
        viewCountService.increaseView(1L);
        viewCountService.flushViewCounts();
        clearInvocations(postRepository);

        viewCountService.increaseView(1L);
        viewCountService.increaseView(1L);

        assertThat(viewCountService.getPendingCount(1L)).isEqualTo(2);
        verifyNoInteractions(postRepository);
    }

    @Test
    @DisplayName("flushViewCounts: DB 오류 발생 시 해당 카운트를 pendingCounts에 복원한다")
    void flushViewCounts_dbError_restoresPending() {
        viewCountService.increaseView(1L);
        viewCountService.increaseView(1L);
        doThrow(new RuntimeException("DB error")).when(postRepository).increaseViewCountBy(1L, 2L);

        viewCountService.flushViewCounts();

        assertThat(viewCountService.getPendingCount(1L)).isEqualTo(2);
    }

    @Test
    @DisplayName("increaseView: 여러 스레드에서 동시에 호출해도 조회수가 정확히 누적된다")
    void increaseView_concurrent() throws InterruptedException {
        int threadCount = 100;
        Long postId = 1L;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    viewCountService.increaseView(postId);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executor.shutdown();

        assertThat(viewCountService.getPendingCount(postId)).isEqualTo(threadCount);
    }

    @Test
    @DisplayName("onShutdown: 종료 시 미반영 카운트를 flush한다")
    void onShutdown_flushesPending() {
        viewCountService.increaseView(1L);

        viewCountService.onShutdown();

        verify(postRepository).increaseViewCountBy(1L, 1L);
        assertThat(viewCountService.getPendingCount(1L)).isEqualTo(0);
    }

    @Test
    @DisplayName("flushViewCounts: flush 후 postDetail 캐시를 evict한다")
    void flushViewCounts_evictsPostDetailCache() {
        Cache mockCache = mock(Cache.class);
        when(cacheManager.getCache(CacheConfig.POST_DETAIL_CACHE)).thenReturn(mockCache);

        viewCountService.increaseView(1L);
        viewCountService.increaseView(2L);

        viewCountService.flushViewCounts();

        verify(mockCache).evict(1L);
        verify(mockCache).evict(2L);
    }

    @Test
    @DisplayName("flushViewCounts: 캐시가 없으면 evict를 건너뛴다")
    void flushViewCounts_noCacheAvailable_skipsEviction() {
        when(cacheManager.getCache(CacheConfig.POST_DETAIL_CACHE)).thenReturn(null);

        viewCountService.increaseView(1L);

        viewCountService.flushViewCounts();

        verify(postRepository).increaseViewCountBy(1L, 1L);
        verify(cacheManager).getCache(CacheConfig.POST_DETAIL_CACHE);
    }

    @Test
    @DisplayName("flushViewCounts: DB 오류 시 캐시를 evict하지 않는다")
    void flushViewCounts_dbError_doesNotEvictCache() {
        Cache mockCache = mock(Cache.class);
        when(cacheManager.getCache(CacheConfig.POST_DETAIL_CACHE)).thenReturn(mockCache);
        doThrow(new RuntimeException("DB error")).when(postRepository).increaseViewCountBy(1L, 2L);

        viewCountService.increaseView(1L);
        viewCountService.increaseView(1L);

        viewCountService.flushViewCounts();

        verify(mockCache, never()).evict(1L);
    }
}
