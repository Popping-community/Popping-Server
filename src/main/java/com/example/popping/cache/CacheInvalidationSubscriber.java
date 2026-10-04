package com.example.popping.cache;

import java.io.IOException;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies evictions published by the other instances to this instance's local caches.
 *
 * <p>Evicting does not make the next read fresh by itself: the cache refill reads the Replica,
 * which may not have applied the write yet. This only removes the entry this JVM was holding.
 *
 * <p>Outcomes are counted under {@code cache.invalidation.receive} with a {@code result} tag of
 * {@code evicted}, {@code own}, {@code no_cache} or {@code invalid}.
 */
@Slf4j
public class CacheInvalidationSubscriber implements MessageListener {

	static final String METRIC = "cache.invalidation.receive";

	private final CacheManager cacheManager;
	private final ObjectMapper objectMapper;
	private final InstanceId instanceId;
	private final MeterRegistry meterRegistry;

	public CacheInvalidationSubscriber(CacheManager cacheManager, ObjectMapper objectMapper,
			InstanceId instanceId, MeterRegistry meterRegistry) {
		this.cacheManager = cacheManager;
		this.objectMapper = objectMapper;
		this.instanceId = instanceId;
		this.meterRegistry = meterRegistry;
	}

	@Override
	public void onMessage(Message message, byte[] pattern) {
		CacheInvalidationMessage invalidation;
		try {
			invalidation = objectMapper.readValue(message.getBody(), CacheInvalidationMessage.class);
		} catch (IOException e) {
			log.warn("Ignoring unreadable cache invalidation message", e);
			count("invalid");
			return;
		}
		// A body of the JSON literal null deserializes to null rather than failing.
		if (invalidation == null || invalidation.cacheName() == null || invalidation.key() == null) {
			count("invalid");
			return;
		}
		if (instanceId.value().equals(invalidation.origin())) {
			// This instance already evicted locally before publishing.
			count("own");
			return;
		}
		Cache cache = cacheManager.getCache(invalidation.cacheName());
		if (cache == null) {
			// This instance does not register that cache, e.g. postDetail when disabled.
			count("no_cache");
			return;
		}
		cache.evict(invalidation.key());
		count("evicted");
	}

	private void count(String result) {
		meterRegistry.counter(METRIC, "result", result).increment();
	}
}
