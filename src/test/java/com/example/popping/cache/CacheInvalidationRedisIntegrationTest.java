package com.example.popping.cache;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.RedisClientInfo;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Two "instances" in one JVM, each with its own cache, id and subscription, talking through
 * a real Redis. Runs only when a disposable Redis is provided on 127.0.0.1.
 */
@EnabledIfSystemProperty(named = "portfolio.test.redis.port", matches = "[0-9]+")
class CacheInvalidationRedisIntegrationTest {

	private static final String CHANNEL = "test:cache-invalidation";
	private static final String CACHE = "boardFirstPage";
	private static final long DELIVERY_WAIT_SECONDS = 5;
	/** The container retries a lost subscription every 5 seconds by default. */
	private static final long RESUBSCRIBE_WAIT_SECONDS = 30;

	private final ObjectMapper objectMapper = new ObjectMapper();
	private LettuceConnectionFactory factory;
	private StringRedisTemplate redis;
	private RedisMessageListenerContainer receiverContainer;
	private RedisCacheInvalidationBroadcaster sender;
	private Cache receiverCache;

	@BeforeEach
	void setUp() {
		factory = new LettuceConnectionFactory("127.0.0.1", Integer.getInteger("portfolio.test.redis.port"));
		factory.afterPropertiesSet();
		factory.start();
		redis = new StringRedisTemplate(factory);

		sender = new RedisCacheInvalidationBroadcaster(
				redis, objectMapper, new InstanceId("A"), CHANNEL, new SimpleMeterRegistry());

		ConcurrentMapCacheManager receiverCaches = new ConcurrentMapCacheManager(CACHE);
		receiverCache = receiverCaches.getCache(CACHE);
		receiverContainer = new RedisMessageListenerContainer();
		receiverContainer.setConnectionFactory(factory);
		receiverContainer.addMessageListener(new CacheInvalidationSubscriber(
				receiverCaches, objectMapper, new InstanceId("B"), new SimpleMeterRegistry()),
				new ChannelTopic(CHANNEL));
		receiverContainer.afterPropertiesSet();
		receiverContainer.start();
	}

	@AfterEach
	void tearDown() throws Exception {
		sender.destroy();
		receiverContainer.destroy();
		factory.destroy();
	}

	@Test
	@DisplayName("A가 발행한 무효화가 B의 Long 키 엔트리를 지운다 (AC1)")
	void evictionReachesOtherInstance() {
		receiverCache.put(3L, "stale page");

		sender.broadcast(CACHE, 3L);

		await().atMost(DELIVERY_WAIT_SECONDS, TimeUnit.SECONDS)
				.until(() -> receiverCache.get(3L) == null);
	}

	@Test
	@DisplayName("구독 연결이 끊겨도 컨테이너가 다시 구독해 이후 무효화를 받는다")
	void resubscribesAfterSubscriptionConnectionIsKilled() {
		killPubSubConnections();

		receiverCache.put(7L, "stale page");

		// Messages published while unsubscribed are lost, so keep publishing until one lands.
		await().atMost(RESUBSCRIBE_WAIT_SECONDS, TimeUnit.SECONDS)
				.pollInterval(500, TimeUnit.MILLISECONDS)
				.until(() -> {
					sender.broadcast(CACHE, 7L);
					return receiverCache.get(7L) == null;
				});
	}

	private void killPubSubConnections() {
		Integer killed = redis.execute((RedisCallback<Integer>) connection -> {
			int count = 0;
			for (RedisClientInfo client : connection.serverCommands().getClientList()) {
				// A subscribed connection reports at least one channel in "sub".
				if (Long.parseLong(client.get("sub")) > 0) {
					String[] address = client.getAddressPort().split(":");
					connection.serverCommands().killClient(address[0], Integer.parseInt(address[1]));
					count++;
				}
			}
			return count;
		});
		assertThat(killed).isPositive();
	}
}
