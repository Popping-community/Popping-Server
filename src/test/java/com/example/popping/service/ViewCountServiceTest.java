package com.example.popping.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

    ViewCountService viewCountService;

    @BeforeEach
    void setUp() {
        viewCountService = new ViewCountService(postRepository, txTemplate, cacheManager, new SimpleMeterRegistry());
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
    @DisplayName("동시 조회와 flush 후 반영 합계가 접수한 증가분과 일치한다")
    void concurrentIncrementsAndFlush_preserveEveryIncrement() throws Exception {
        int writers = 8;
        int incrementsPerWriter = 20_000;
        AtomicLong persisted = new AtomicLong();
        doAnswer(inv -> {
            persisted.addAndGet(inv.getArgument(1, Long.class));
            return null;
        }).when(postRepository).increaseViewCountBy(eq(1L), anyLong());
        ExecutorService executor = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < writers; i++) {
                tasks.add(executor.submit(() -> {
                    try {
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("Start barrier timed out");
                        }
                        for (int j = 0; j < incrementsPerWriter; j++) {
                            viewCountService.increaseView(1L);
                        }
                        return null;
                    } finally {
                        done.countDown();
                    }
                }));
            }
            tasks.add(executor.submit(() -> {
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Start barrier timed out");
                }
                do {
                    viewCountService.flushViewCounts();
                } while (done.getCount() > 0 && !Thread.currentThread().isInterrupted());
                return null;
            }));
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(20, TimeUnit.SECONDS);
            }
            viewCountService.onShutdown();
            assertThat(persisted.get()).isEqualTo((long) writers * incrementsPerWriter);
            assertThat(viewCountService.getPendingCount(1L)).isZero();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("DB 확정 실패 중 새 조회가 들어와도 복원 후 재시도 합계가 보존된다")
    void failedFlushWithNewIncrement_retryPreservesBoth() {
        viewCountService.increaseView(1L);
        viewCountService.increaseView(1L);
        doAnswer(inv -> {
            viewCountService.increaseView(1L);
            throw new IllegalStateException("Rejected before applying update");
        }).when(postRepository).increaseViewCountBy(1L, 2L);

        viewCountService.flushViewCounts();

        assertThat(viewCountService.getPendingCount(1L)).isEqualTo(3L);
        viewCountService.flushViewCounts();
        verify(postRepository).increaseViewCountBy(1L, 3L);
        assertThat(viewCountService.getPendingCount(1L)).isZero();
    }

    @Test
    @DisplayName("DB 반영 대기 중 새 증가분을 다른 flush가 처리해도 중복하거나 잃지 않는다")
    void overlappingFlushes_ownDistinctBatches() throws Exception {
        CountDownLatch firstWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        AtomicLong persisted = new AtomicLong();
        doAnswer(inv -> {
            long delta = inv.getArgument(1, Long.class);
            if (delta == 2L) {
                firstWriteEntered.countDown();
                if (!releaseFirstWrite.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("First write was not released");
                }
            }
            persisted.addAndGet(delta);
            return null;
        }).when(postRepository).increaseViewCountBy(eq(1L), anyLong());
        viewCountService.increaseView(1L);
        viewCountService.increaseView(1L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(viewCountService::flushViewCounts);
            assertThat(firstWriteEntered.await(10, TimeUnit.SECONDS)).isTrue();
            viewCountService.increaseView(1L);
            assertThat(viewCountService.getPendingCount(1L)).isEqualTo(1L);
            viewCountService.flushViewCounts();
            assertThat(persisted.get()).isEqualTo(1L);
            releaseFirstWrite.countDown();
            first.get(10, TimeUnit.SECONDS);
            viewCountService.onShutdown();
            assertThat(persisted.get()).isEqualTo(3L);
            assertThat(viewCountService.getPendingCount(1L)).isZero();
            verify(postRepository, times(2)).increaseViewCountBy(eq(1L), anyLong());
        } finally {
            releaseFirstWrite.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("종료 flush의 DB 실패는 메모리에 복원할 뿐 내구성을 보장하지 않는다")
    void shutdownWriteFailure_leavesPendingInMemory() {
        viewCountService.increaseView(1L);
        doThrow(new IllegalStateException("Rejected before applying update"))
                .when(postRepository).increaseViewCountBy(1L, 1L);

        viewCountService.onShutdown();

        assertThat(viewCountService.getPendingCount(1L)).isEqualTo(1L);
        verify(postRepository).increaseViewCountBy(1L, 1L);
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
