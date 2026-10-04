package com.example.popping.relay;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Chooses how a like update reaches viewers connected to the other application instances.
 *
 * <p>{@code app.websocket.relay=local} (the default) relays nothing, which is right for a single
 * instance and for environments without Redis. {@code redis} publishes every update on a Redis
 * channel and re-delivers the other instances' updates to this instance's broker. Any other
 * value leaves no {@link LikeRelay} bean, so startup fails instead of silently not relaying.
 *
 * <p>In {@code redis} mode an unreachable Redis fails startup, because the listener container's
 * {@code start()} throws when it cannot subscribe: an instance up without the subscription
 * would leave its viewers behind with nothing to say so. Once running, a Redis outage only
 * costs relaying; likes and same-instance delivery are unaffected.
 */
@Configuration
public class LikeRelayConfig {

	public static final String LIKE_DESTINATION = "/topic/like-updates";
	public static final String CHANNEL = "popping:like-updates";
	private static final int LISTENER_QUEUE_CAPACITY = 1_000;

	@Configuration
	@ConditionalOnProperty(name = "app.websocket.relay", havingValue = "local", matchIfMissing = true)
	static class Local {

		@Bean
		LikeRelay likeRelay() {
			return new NoOpLikeRelay();
		}
	}

	@Configuration
	@ConditionalOnProperty(name = "app.websocket.relay", havingValue = "redis")
	static class Redis {

		// One id per JVM, shared by publisher and subscriber so this instance skips its own updates.
		private final String origin = UUID.randomUUID().toString();

		@Bean
		LikeRelay likeRelay(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
				MeterRegistry meterRegistry) {
			return new RedisLikeRelay(redisTemplate, objectMapper, origin, CHANNEL, meterRegistry);
		}

		@Bean
		LikeRelaySubscriber likeRelaySubscriber(SimpMessagingTemplate messagingTemplate,
				ObjectMapper objectMapper, MeterRegistry meterRegistry) {
			return new LikeRelaySubscriber(messagingTemplate, objectMapper, origin, LIKE_DESTINATION, meterRegistry);
		}

		/**
		 * Runs the subscriber. Without its own executor the container hands every message to a
		 * new thread; one thread with a bounded queue keeps delivery ordered and memory bounded.
		 */
		@Bean
		ThreadPoolTaskExecutor likeRelayListenerExecutor(MeterRegistry meterRegistry) {
			return listenerExecutor(meterRegistry, LISTENER_QUEUE_CAPACITY);
		}

		@Bean
		RedisMessageListenerContainer likeRelayListenerContainer(RedisConnectionFactory connectionFactory,
				LikeRelaySubscriber subscriber, ThreadPoolTaskExecutor likeRelayListenerExecutor) {
			RedisMessageListenerContainer container = new RedisMessageListenerContainer();
			container.setConnectionFactory(connectionFactory);
			container.setTaskExecutor(likeRelayListenerExecutor);
			container.addMessageListener(subscriber, new ChannelTopic(CHANNEL));
			return container;
		}
	}

	/**
	 * A full queue drops the update, like a lost Pub/Sub message, and counts it as
	 * {@code dropped} under the subscriber's metric instead of throwing into the container.
	 */
	static ThreadPoolTaskExecutor listenerExecutor(MeterRegistry meterRegistry, int queueCapacity) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(queueCapacity);
		executor.setThreadNamePrefix("like-relay-listener-");
		executor.setRejectedExecutionHandler((task, pool) ->
				meterRegistry.counter(LikeRelaySubscriber.METRIC, "result", "dropped").increment());
		return executor;
	}
}
