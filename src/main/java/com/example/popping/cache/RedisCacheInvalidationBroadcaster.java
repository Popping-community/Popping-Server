package com.example.popping.cache;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes evictions on a Redis channel from a single background thread.
 *
 * <p>The caller is a committed write request. Publishing on its thread would make the
 * request wait for Redis, up to the client's command timeout when Redis hangs, so the
 * message is handed to a bounded queue instead. When the queue is full the message is
 * dropped: the other instances then keep the stale entry until its TTL expires, which is
 * the same outcome as a lost Pub/Sub message. One thread also keeps this instance's
 * messages in publish order.
 *
 * <p>Outcomes are counted under {@code cache.invalidation.publish} with a {@code result}
 * tag of {@code sent}, {@code failed} or {@code dropped}.
 */
@Slf4j
public class RedisCacheInvalidationBroadcaster implements CacheInvalidationBroadcaster, DisposableBean {

	static final String METRIC = "cache.invalidation.publish";
	private static final int QUEUE_CAPACITY = 1_000;
	private static final long SHUTDOWN_WAIT_SECONDS = 2;

	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;
	private final InstanceId instanceId;
	private final String channel;
	private final MeterRegistry meterRegistry;
	private final ThreadPoolExecutor executor;

	public RedisCacheInvalidationBroadcaster(StringRedisTemplate redis, ObjectMapper objectMapper,
			InstanceId instanceId, String channel, MeterRegistry meterRegistry) {
		this(redis, objectMapper, instanceId, channel, meterRegistry, QUEUE_CAPACITY);
	}

	RedisCacheInvalidationBroadcaster(StringRedisTemplate redis, ObjectMapper objectMapper,
			InstanceId instanceId, String channel, MeterRegistry meterRegistry, int queueCapacity) {
		this.redis = redis;
		this.objectMapper = objectMapper;
		this.instanceId = instanceId;
		this.channel = channel;
		this.meterRegistry = meterRegistry;
		this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(queueCapacity), runnable -> {
					Thread thread = new Thread(runnable, "cache-invalidation-publisher");
					thread.setDaemon(true);
					return thread;
				}, new ThreadPoolExecutor.AbortPolicy());
	}

	@Override
	public void broadcast(String cacheName, Long key) {
		if (cacheName == null || key == null) {
			return;
		}
		try {
			executor.execute(() -> publish(cacheName, key));
		} catch (RejectedExecutionException e) {
			// Also thrown after shutdown, when the instance is going away anyway.
			log.warn("Cache invalidation dropped, publish queue full cache={} key={}", cacheName, key);
			count("dropped");
		} catch (RuntimeException e) {
			// The contract is to never reach the committed caller, whatever went wrong here.
			log.warn("Cache invalidation not queued cache={} key={}", cacheName, key, e);
			count("failed");
		}
	}

	private void publish(String cacheName, Long key) {
		try {
			String payload = objectMapper.writeValueAsString(
					new CacheInvalidationMessage(cacheName, key, instanceId.value()));
			redis.convertAndSend(channel, payload);
			count("sent");
		} catch (JsonProcessingException | RuntimeException e) {
			log.warn("Cache invalidation publish failed cache={} key={}", cacheName, key, e);
			count("failed");
		}
	}

	private void count(String result) {
		try {
			meterRegistry.counter(METRIC, "result", result).increment();
		} catch (RuntimeException e) {
			// Called from broadcast's catch blocks too, so a metrics failure must not escape either.
			log.warn("Cache invalidation metric not recorded result={}", result, e);
		}
	}

	@Override
	public void destroy() {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
				// Whatever is still queued is lost, like any undelivered Pub/Sub message.
				int abandoned = executor.shutdownNow().size();
				log.warn("Cache invalidation publisher stopped with {} message(s) unsent", abandoned);
			}
		} catch (InterruptedException e) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}
}
