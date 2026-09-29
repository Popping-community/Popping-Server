package com.example.popping.service;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.popping.repository.PostRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ViewCountMetricsTest {
    private final PostRepository repository = mock(PostRepository.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private ViewCountService service;

    @BeforeEach
    void setup() {
        service = new ViewCountService(repository, transaction, mock(CacheManager.class), registry);
        doAnswer(call -> {
            Consumer<TransactionStatus> callback = call.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transaction).executeWithoutResult(any());
    }

    private double gauge(String suffix) {
        return registry.get("popping.view.count." + suffix).gauge().value();
    }

    private Timer writes(String result) {
        return registry.get("popping.view.count.write").tag("result", result).timer();
    }

    @Test
    void emptyFlushDoesNotInventAnAttempt() {
        service.flushViewCounts();
        assertThat(gauge("pending")).isZero();
        assertThat(gauge("in.flight")).isZero();
        assertThat(writes("returned").count()).isZero();
        assertThat(writes("error").count()).isZero();
    }

    @Test
    void blockedBatchIncludesNotYetAttemptedEntriesAndKeepsNewReadsPending() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.getAndIncrement() == 0) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Unreleased write");
            }
            clock.add(Duration.ofMillis(250));
            return null;
        }).when(repository).increaseViewCountBy(anyLong(), anyLong());
        service.increaseView(1L);
        service.increaseView(1L);
        service.increaseView(2L);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var flush = executor.submit(service::flushViewCounts);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(gauge("pending")).isZero();
            assertThat(gauge("in.flight")).isEqualTo(3);
            assertThat(writes("returned").count()).isZero();
            service.increaseView(3L);
            assertThat(gauge("pending")).isEqualTo(1);
            release.countDown();
            flush.get(5, TimeUnit.SECONDS);
            assertThat(gauge("in.flight")).isZero();
            assertThat(gauge("pending")).isEqualTo(1);
            assertThat(writes("returned").count()).isEqualTo(2);
            assertThat(writes("returned").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(500);
            System.out.println("METRICS blocked: pending=0 in_flight=3 completed=0; released: pending=1 in_flight=0 returned=2");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void transactionFailureRestoresDeltaAndRetryCountsAsAnotherAttempt() {
        service.increaseView(1L);
        service.increaseView(1L);
        doAnswer(call -> {
            Consumer<TransactionStatus> callback = call.getArgument(0);
            callback.accept(null);
            service.increaseView(1L);
            clock.add(Duration.ofMillis(400));
            throw new IllegalStateException("Simulated transaction completion failure");
        }).doAnswer(call -> {
            Consumer<TransactionStatus> callback = call.getArgument(0);
            callback.accept(null);
            clock.add(Duration.ofMillis(100));
            return null;
        }).when(transaction).executeWithoutResult(any());
        service.flushViewCounts();
        assertThat(gauge("pending")).isEqualTo(3);
        assertThat(gauge("in.flight")).isZero();
        assertThat(writes("error").count()).isEqualTo(1);
        assertThat(writes("error").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(400);
        assertThat(writes("returned").count()).isZero();
        service.flushViewCounts();
        assertThat(gauge("pending")).isZero();
        assertThat(gauge("in.flight")).isZero();
        assertThat(writes("returned").count()).isEqualTo(1);
        assertThat(writes("returned").totalTime(TimeUnit.MILLISECONDS)).isEqualTo(100);
        assertThat(writes("error").count()).isEqualTo(1);
        verify(repository).increaseViewCountBy(1L, 3L);
    }

    @Test
    void overlappingFlushesDoNotClearAnotherBatchGauge() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Unreleased write");
            return null;
        }).when(repository).increaseViewCountBy(1L, 2L);
        service.increaseView(1L);
        service.increaseView(1L);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(service::flushViewCounts);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            service.increaseView(1L);
            service.flushViewCounts();
            assertThat(gauge("in.flight")).isEqualTo(2);
            assertThat(writes("returned").count()).isEqualTo(1);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(gauge("in.flight")).isZero();
            assertThat(writes("returned").count()).isEqualTo(2);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void abnormalAbortClearsBatchOwnershipWithoutClaimingRecovery() {
        service.increaseView(1L);
        service.increaseView(2L);
        doThrow(new AssertionError("Simulated fatal write error"))
                .when(repository).increaseViewCountBy(anyLong(), anyLong());

        assertThatThrownBy(service::flushViewCounts).isInstanceOf(AssertionError.class);

        assertThat(gauge("in.flight")).isZero();
        assertThat(gauge("pending")).isZero();
        assertThat(writes("error").count()).isEqualTo(1);
        assertThat(writes("returned").count()).isZero();
        verify(repository, times(1)).increaseViewCountBy(anyLong(), anyLong());
    }

    @Test
    void prometheusExportsFixedLabelsAndAttemptCountRatherThanViewCount() {
        var prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var observed = new ViewCountService(repository, transaction, mock(CacheManager.class), prometheus);
            observed.increaseView(987654L);
            observed.increaseView(987654L);
            assertThat(prometheus.scrape()).contains("popping_view_count_pending 2.0");
            observed.flushViewCounts();
            String scrape = prometheus.scrape();
            assertThat(scrape).contains("popping_view_count_pending 0.0", "popping_view_count_in_flight 0.0",
                    "popping_view_count_write_seconds_count{result=\"returned\"} 1",
                    "popping_view_count_write_seconds_count{result=\"error\"} 0");
            assertThat(scrape).doesNotContain("987654", "postId", "post_id");
            System.out.println("PROMETHEUS_SCRAPE\n" + scrape);
        } finally {
            prometheus.close();
        }
    }
}
