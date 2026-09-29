package com.example.popping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import jakarta.annotation.PreDestroy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.LifecycleAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.SpringVersion;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.popping.repository.PostRepository;

class ViewCountShutdownLifecycleTest {

    @Test
    @DisplayName("Spring 종료는 진행 중 scheduled flush 완료 후 잔여분과 의존 자원을 정리한다")
    void contextClose_waitsForScheduledFlushBeforeDestroyingDependencies() {
        verifyClose(false, false);
    }

    @Test
    @DisplayName("기한 안에 실패한 scheduled flush 복원분은 종료 훅이 새 증가분과 함께 재시도한다")
    void contextClose_retriesConfirmedFailureBeforeDependencyDestruction() {
        verifyClose(true, false);
    }

    @Test
    @DisplayName("Spring lifecycle 기한을 넘기면 진행 중 flush 완료 없이 의존 자원 정리가 진행된다")
    void contextClose_timeoutDoesNotGuaranteeInFlightCompletion() {
        verifyClose(false, true);
    }

    private void verifyClose(boolean failFirstWrite, boolean exceedDeadline) {
        Probe probe = new Probe(failFirstWrite);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TaskSchedulingAutoConfiguration.class, LifecycleAutoConfiguration.class))
                .withUserConfiguration(Fixture.class)
                .withBean(Probe.class, () -> probe)
                .withPropertyValues("spring.threads.virtual.enabled=false",
                        "spring.lifecycle.timeout-per-shutdown-phase=" + (exceedDeadline ? "200ms" : "5s"))
                .run(context -> {
                    assertThat(context).hasSingleBean(ThreadPoolTaskScheduler.class);
                    var scheduler = context.getBean(ThreadPoolTaskScheduler.class);
                    var service = context.getBean(ViewCountService.class);
                    var storage = context.getBean(Storage.class);
                    assertThat(probe.entered.await(5, TimeUnit.SECONDS)).isTrue();
                    if (!exceedDeadline) {
                        service.increaseView(1L);
                    }
                    context.getSourceApplicationContext().addApplicationListener(event -> {
                        if (event instanceof ContextClosedEvent) {
                            probe.events.add("context-close");
                            probe.closeStarted.countDown();
                        }
                    });
                    var closer = Executors.newSingleThreadExecutor();
                    try {
                        var closing = closer.submit(context.getSourceApplicationContext()::close);
                        assertThat(probe.closeStarted.await(5, TimeUnit.SECONDS)).isTrue();
                        if (exceedDeadline) {
                            closing.get(5, TimeUnit.SECONDS);
                            assertThat(probe.finished.getCount()).isEqualTo(1);
                            assertThat(probe.events).contains("pre-destroy", "storage-destroyed");
                            assertThat(storage.persisted.get()).isZero();
                            probe.release.countDown();
                            assertThat(probe.finished.await(5, TimeUnit.SECONDS)).isTrue();
                            assertThat(scheduler.getScheduledThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
                            assertThat(service.getPendingCount(1L)).isEqualTo(2);
                            assertThat(probe.events).contains("write-after-destroy-rejected");
                        } else {
                            assertThatThrownBy(() -> closing.get(150, TimeUnit.MILLISECONDS))
                                    .isInstanceOf(TimeoutException.class);
                            assertThat(probe.events).doesNotContain("pre-destroy", "storage-destroyed", "interrupted");
                            probe.release.countDown();
                            closing.get(5, TimeUnit.SECONDS);
                            assertThat(storage.persisted.get()).isEqualTo(3);
                            assertThat(service.getPendingCount(1L)).isZero();
                            assertThat(probe.events).containsSubsequence("scheduled-write-returned",
                                    "pre-destroy", "shutdown-returned", "storage-destroyed");
                            assertThat(probe.events).doesNotContain("interrupted");
                        }
                        System.out.printf("LIFECYCLE failFirst=%s timeout=%s boot=%s spring=%s persisted=%d pending=%d events=%s%n",
                                failFirstWrite, exceedDeadline, SpringBootVersion.getVersion(), SpringVersion.getVersion(),
                                storage.persisted.get(), service.getPendingCount(1L), probe.events);
                    } finally {
                        probe.release.countDown();
                        closer.shutdownNow();
                        assertThat(closer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
                        scheduler.getScheduledThreadPoolExecutor().shutdownNow();
                        assertThat(scheduler.getScheduledThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
                    }
                });
    }

    static class Probe {
        final boolean failFirst;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final CountDownLatch closeStarted = new CountDownLatch(1);
        final List<String> events = new CopyOnWriteArrayList<>();

        Probe(boolean failFirst) {
            this.failFirst = failFirst;
        }
    }

    static class Storage {
        final Probe probe;
        final AtomicLong persisted = new AtomicLong();
        final AtomicInteger calls = new AtomicInteger();
        final PostRepository repository = mock(PostRepository.class);
        volatile boolean destroyed;

        Storage(Probe probe) {
            this.probe = probe;
            doAnswer(invocation -> {
                boolean first = calls.getAndIncrement() == 0;
                if (first) {
                    probe.events.add("scheduled-write-entered");
                    probe.entered.countDown();
                    // Model a driver that does not finish merely because it is interrupted.
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    while (probe.release.getCount() > 0) {
                        try {
                            if (!probe.release.await(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                                throw new AssertionError("Write barrier was never released");
                            }
                        } catch (InterruptedException ignored) {
                            probe.events.add("interrupted");
                        }
                    }
                }
                try {
                    if (destroyed) {
                        probe.events.add("write-after-destroy-rejected");
                        throw new IllegalStateException("Test storage was destroyed before write completion");
                    }
                    if (first && probe.failFirst) {
                        throw new IllegalStateException("Test write rejected before applying delta");
                    }
                    persisted.addAndGet(invocation.getArgument(1, Long.class));
                    return null;
                } finally {
                    if (first) {
                        probe.events.add("scheduled-write-returned");
                        probe.finished.countDown();
                    }
                }
            }).when(repository).increaseViewCountBy(anyLong(), anyLong());
        }

        public void close() {
            destroyed = true;
            probe.events.add("storage-destroyed");
        }
    }

    static class ObservedService extends ViewCountService {
        private final Probe probe;

        ObservedService(Storage storage, TransactionTemplate transaction) {
            super(storage.repository, transaction, mock(CacheManager.class),
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
            this.probe = storage.probe;
        }

        @Override
        @PreDestroy
        public void onShutdown() {
            probe.events.add("pre-destroy");
            super.onShutdown();
            probe.events.add("shutdown-returned");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableScheduling
    static class Fixture {
        @Bean(destroyMethod = "close")
        Storage storage(Probe probe) {
            return new Storage(probe);
        }

        @Bean
        ViewCountService viewCountService(Storage storage) {
            TransactionTemplate transaction = mock(TransactionTemplate.class);
            doAnswer(invocation -> {
                Consumer<TransactionStatus> callback = invocation.getArgument(0);
                callback.accept(null);
                return null;
            }).when(transaction).executeWithoutResult(any());
            ViewCountService service = new ObservedService(storage, transaction);
            service.increaseView(1L);
            service.increaseView(1L);
            return service;
        }
    }
}
