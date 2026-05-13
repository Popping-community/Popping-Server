package com.example.popping.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import jakarta.annotation.PreDestroy;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.example.popping.repository.PostRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class ViewCountService {

    private final PostRepository postRepository;
    private final TransactionTemplate txTemplate;

    private final ConcurrentHashMap<Long, LongAdder> pendingCounts = new ConcurrentHashMap<>();

    public void increaseView(Long postId) {
        pendingCounts.computeIfAbsent(postId, k -> new LongAdder()).increment();
    }

    public long getPendingCount(Long postId) {
        LongAdder adder = pendingCounts.get(postId);
        return adder != null ? adder.sum() : 0;
    }

    @Scheduled(fixedRate = 30_000)
    public void flushViewCounts() {
        if (pendingCounts.isEmpty()) {
            return;
        }

        // Replace each adder with a fresh one so concurrent increaseView() calls
        // go to the new adder (flushed in next cycle). We then read the old adder
        // AFTER replace — any thread that already holds the old reference and
        // increments it before our sum() call is still captured.
        List<Map.Entry<Long, Long>> batch = new ArrayList<>();
        for (Long postId : pendingCounts.keySet()) {
            LongAdder fresh = new LongAdder();
            LongAdder old = pendingCounts.replace(postId, fresh);
            if (old == null) {
                continue;
            }
            long count = old.sum();
            if (count > 0) {
                batch.add(Map.entry(postId, count));
            }
            // Remove the fresh adder if no new increments arrived during this loop
            pendingCounts.remove(postId, fresh);
        }

        int flushed = 0;
        for (Map.Entry<Long, Long> entry : batch) {
            try {
                txTemplate.executeWithoutResult(status ->
                        postRepository.increaseViewCountBy(entry.getKey(), entry.getValue()));
                flushed++;
            } catch (Exception e) {
                log.warn("Failed to flush viewCount for postId={}", entry.getKey(), e);
                pendingCounts.computeIfAbsent(entry.getKey(), k -> new LongAdder())
                        .add(entry.getValue());
            }
        }

        if (flushed > 0) {
            log.info("viewCount flush: updated {} posts", flushed);
        }
    }

    @PreDestroy
    public void onShutdown() {
        log.info("Flushing pending view counts before shutdown...");
        flushViewCounts();
    }
}
