package com.example.popping.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.extern.slf4j.Slf4j;

import com.example.popping.config.app.CacheConfig;
import com.example.popping.repository.PostRepository;

@Slf4j
@Service
public class ViewCountService {

    private final PostRepository postRepository;
    private final TransactionTemplate txTemplate;
    private final CacheManager cacheManager;
    private final MeterRegistry meterRegistry;
    private final Timer returnedWrites;
    private final Timer failedWrites;

    private final ConcurrentHashMap<Long, Long> pendingCounts = new ConcurrentHashMap<>();
    private final AtomicLong inFlightCounts = new AtomicLong();

    public ViewCountService(PostRepository postRepository, TransactionTemplate txTemplate,
            CacheManager cacheManager, MeterRegistry meterRegistry) {
        this.postRepository = postRepository;
        this.txTemplate = txTemplate;
        this.cacheManager = cacheManager;
        this.meterRegistry = meterRegistry;
        Gauge.builder("popping.view.count.pending", pendingCounts,
                        counts -> counts.values().stream().mapToLong(Long::longValue).sum())
                .description("Increments in memory awaiting batch transfer; sampled, not a shutdown gate")
                .register(meterRegistry);
        Gauge.builder("popping.view.count.in.flight", inFlightCounts, AtomicLong::doubleValue)
                .description("Increments owned by active batches, including entries awaiting their DB attempt")
                .register(meterRegistry);
        returnedWrites = writeTimer("returned");
        failedWrites = writeTimer("error");
    }

    private Timer writeTimer(String result) {
        return Timer.builder("popping.view.count.write")
                .tag("result", result)
                .description("Per-entry transaction attempts including error restoration; returned is not a durability guarantee")
                .register(meterRegistry);
    }

    public void increaseView(Long postId) {
        pendingCounts.merge(postId, 1L, Long::sum);
    }

    public long getPendingCount(Long postId) {
        return pendingCounts.getOrDefault(postId, 0L);
    }

    @Scheduled(fixedRate = 30_000)
    public void flushViewCounts() {
        if (pendingCounts.isEmpty()) {
            return;
        }

        // Atomically transfer each delta to this batch. Later increments stay
        // in the map; no mutable counter reference can escape the transfer.
        List<Map.Entry<Long, Long>> batch = new ArrayList<>();
        for (Long postId : pendingCounts.keySet()) {
            Long count = pendingCounts.remove(postId);
            if (count != null && count > 0) {
                batch.add(Map.entry(postId, count));
            }
        }

        // Transfer and metric sampling are not one atomic snapshot.
        long remaining = batch.stream().mapToLong(Map.Entry::getValue).sum();
        inFlightCounts.addAndGet(remaining);
        int flushed = 0;
        try {
            for (Map.Entry<Long, Long> entry : batch) {
                if (writeEntry(entry)) {
                    flushed++;
                }
                inFlightCounts.addAndGet(-entry.getValue());
                remaining -= entry.getValue();
            }
        } finally {
            // Also release ownership if an unexpected error aborts the batch.
            inFlightCounts.addAndGet(-remaining);
        }

        if (flushed > 0) {
            evictPostDetailCache(batch);
            log.info("viewCount flush: updated {} posts", flushed);
        }
    }

    private boolean writeEntry(Map.Entry<Long, Long> entry) {
        Timer.Sample sample = Timer.start(meterRegistry);
        Timer outcome = failedWrites;
        try {
            txTemplate.executeWithoutResult(status ->
                    postRepository.increaseViewCountBy(entry.getKey(), entry.getValue()));
            outcome = returnedWrites;
            return true;
        } catch (Exception e) {
            log.warn("Failed to flush viewCount for postId={}", entry.getKey(), e);
            pendingCounts.merge(entry.getKey(), entry.getValue(), Long::sum);
            return false;
        } finally {
            sample.stop(outcome);
        }
    }

    private void evictPostDetailCache(List<Map.Entry<Long, Long>> batch) {
        Cache cache = cacheManager.getCache(CacheConfig.POST_DETAIL_CACHE);
        if (cache == null) {
            return;
        }
        for (Map.Entry<Long, Long> entry : batch) {
            cache.evict(entry.getKey());
        }
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Flushing pending view counts before shutdown...");
        flushViewCounts();
    }
}
