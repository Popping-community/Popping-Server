package com.example.popping.relay;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.example.popping.dto.LikeResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes like updates on a Redis channel from one background thread.
 *
 * <p>The caller is a like request whose transaction has committed. Publishing on its thread
 * would hold the response until Redis answers, so the update goes to a bounded queue instead.
 * A full queue drops the update: other instances' viewers then stay behind until they refetch,
 * the same outcome as a lost Pub/Sub message. One thread keeps this instance's updates in order.
 *
 * <p>Outcomes are counted under {@code like.relay.publish} with a {@code result} tag of
 * {@code sent}, {@code failed} or {@code dropped}.
 */
@Slf4j
public class RedisLikeRelay implements LikeRelay, DisposableBean {

	static final String METRIC = "like.relay.publish";
	private static final int QUEUE_CAPACITY = 1_000;
	private static final long SHUTDOWN_WAIT_SECONDS = 2;

	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;
	private final String origin;
	private final String channel;
	private final MeterRegistry meterRegistry;
	private final ThreadPoolExecutor executor;

	public RedisLikeRelay(StringRedisTemplate redis, ObjectMapper objectMapper, String origin, String channel,
			MeterRegistry meterRegistry) {
		this(redis, objectMapper, origin, channel, meterRegistry, QUEUE_CAPACITY);
	}

	RedisLikeRelay(StringRedisTemplate redis, ObjectMapper objectMapper, String origin, String channel,
			MeterRegistry meterRegistry, int queueCapacity) {
		this.redis = redis;
		this.objectMapper = objectMapper;
		this.origin = origin;
		this.channel = channel;
		this.meterRegistry = meterRegistry;
		this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(queueCapacity), runnable -> {
					Thread thread = new Thread(runnable, "like-relay-publisher");
					thread.setDaemon(true);
					return thread;
				}, new ThreadPoolExecutor.AbortPolicy());
	}

	@Override
	public void publish(LikeResponse update) {
		if (update == null) {
			return;
		}
		try {
			executor.execute(() -> send(update));
		} catch (RejectedExecutionException e) {
			// Also thrown after shutdown, when the instance is going away anyway.
			log.warn("Like relay dropped, publish queue full target={}:{}", update.targetType(), update.targetId());
			count("dropped");
		} catch (RuntimeException e) {
			log.warn("Like relay not queued target={}:{}", update.targetType(), update.targetId(), e);
			count("failed");
		}
	}

	private void send(LikeResponse update) {
		try {
			String payload = objectMapper.writeValueAsString(new LikeRelayMessage(origin, update));
			redis.convertAndSend(channel, payload);
			count("sent");
		} catch (JsonProcessingException | RuntimeException e) {
			log.warn("Like relay publish failed target={}:{}", update.targetType(), update.targetId(), e);
			count("failed");
		}
	}

	private void count(String result) {
		try {
			meterRegistry.counter(METRIC, "result", result).increment();
		} catch (RuntimeException e) {
			// Called from publish's catch blocks too, so a metrics failure must not escape either.
			log.warn("Like relay metric not recorded result={}", result, e);
		}
	}

	@Override
	public void destroy() {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
				int abandoned = executor.shutdownNow().size();
				log.warn("Like relay publisher stopped with {} update(s) unsent", abandoned);
			}
		} catch (InterruptedException e) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}
}
