package com.example.popping.config.app;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.example.popping.cache.CacheInvalidationBroadcaster;
import com.example.popping.cache.CacheInvalidationSubscriber;
import com.example.popping.cache.InstanceId;
import com.example.popping.cache.NoOpCacheInvalidationBroadcaster;
import com.example.popping.cache.RedisCacheInvalidationBroadcaster;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Chooses how a local cache eviction reaches the other application instances.
 *
 * <p>{@code app.cache.invalidation=local} (the default) tells nobody, which is right for a
 * single instance and for environments without Redis. {@code redis} publishes every
 * eviction on a Redis channel and applies the ones the other instances publish. Any other
 * value leaves no broadcaster bean, so the context fails to start instead of silently
 * running without propagation.
 *
 * <p>In {@code redis} mode an unreachable Redis fails startup: the listener container's
 * {@code start()} throws when it cannot subscribe. That is deliberate - an instance that
 * came up without the subscription would serve stale entries with nothing to say so.
 * Once running, a Redis outage only costs propagation; write requests are unaffected.
 *
 * <p>This is independent of {@code app.session.store}. The Redis connection factory exists
 * in every mode because the Redis starter is always on the classpath; only the beans below
 * depend on this setting.
 */
@Configuration
public class CacheInvalidationConfig {

	public static final String CHANNEL = "popping:cache-invalidation";

	@Bean
	InstanceId instanceId() {
		return InstanceId.random();
	}

	@Configuration
	@ConditionalOnProperty(name = "app.cache.invalidation", havingValue = "local", matchIfMissing = true)
	static class Local {

		@Bean
		CacheInvalidationBroadcaster cacheInvalidationBroadcaster() {
			return new NoOpCacheInvalidationBroadcaster();
		}
	}

	@Configuration
	@ConditionalOnProperty(name = "app.cache.invalidation", havingValue = "redis")
	static class Redis {

		@Bean
		CacheInvalidationBroadcaster cacheInvalidationBroadcaster(StringRedisTemplate redisTemplate,
				ObjectMapper objectMapper, InstanceId instanceId, MeterRegistry meterRegistry) {
			return new RedisCacheInvalidationBroadcaster(
					redisTemplate, objectMapper, instanceId, CHANNEL, meterRegistry);
		}

		@Bean
		CacheInvalidationSubscriber cacheInvalidationSubscriber(CacheManager cacheManager,
				ObjectMapper objectMapper, InstanceId instanceId, MeterRegistry meterRegistry) {
			return new CacheInvalidationSubscriber(cacheManager, objectMapper, instanceId, meterRegistry);
		}

		@Bean
		RedisMessageListenerContainer cacheInvalidationListenerContainer(
				RedisConnectionFactory connectionFactory, CacheInvalidationSubscriber subscriber) {
			RedisMessageListenerContainer container = new RedisMessageListenerContainer();
			container.setConnectionFactory(connectionFactory);
			container.addMessageListener(subscriber, new ChannelTopic(CHANNEL));
			return container;
		}
	}
}
