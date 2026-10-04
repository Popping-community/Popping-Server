package com.example.popping.relay;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.RedisClientInfo;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.example.popping.domain.Like;
import com.example.popping.dto.LikeResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Two "instances" in one JVM, A publishing and both subscribed, talking through a real Redis.
 * Runs only when a disposable Redis is provided on 127.0.0.1.
 */
@EnabledIfSystemProperty(named = "portfolio.test.redis.port", matches = "[0-9]+")
class LikeRelayRedisIntegrationTest {

	private static final String CHANNEL = "test:like-updates";
	private static final String DESTINATION = "/topic/like-updates";
	private static final long DELIVERY_WAIT_SECONDS = 5;
	/** The container retries a lost subscription every 5 seconds by default. */
	private static final long RESUBSCRIBE_WAIT_SECONDS = 30;

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final SimpMessagingTemplate brokerA = mock(SimpMessagingTemplate.class);
	private final SimpMessagingTemplate brokerB = mock(SimpMessagingTemplate.class);
	private LettuceConnectionFactory factory;
	private StringRedisTemplate redis;
	private RedisLikeRelay relayA;
	private RedisMessageListenerContainer containerA;
	private RedisMessageListenerContainer containerB;

	@BeforeEach
	void setUp() {
		factory = new LettuceConnectionFactory("127.0.0.1", Integer.getInteger("portfolio.test.redis.port"));
		factory.afterPropertiesSet();
		factory.start();
		redis = new StringRedisTemplate(factory);
		relayA = new RedisLikeRelay(redis, objectMapper, "A", CHANNEL, new SimpleMeterRegistry());
		containerA = container(new LikeRelaySubscriber(brokerA, objectMapper, "A", DESTINATION, new SimpleMeterRegistry()));
		containerB = container(new LikeRelaySubscriber(brokerB, objectMapper, "B", DESTINATION, new SimpleMeterRegistry()));
	}

	@AfterEach
	void tearDown() throws Exception {
		relayA.destroy();
		containerA.destroy();
		containerB.destroy();
		factory.destroy();
	}

	@Test
	@DisplayName("A에서 발행한 좋아요는 B의 브로커로 가고, A는 자기 것을 다시 보내지 않는다")
	void updateReachesOtherInstanceOnly() {
		LikeResponse update = update(1L);

		relayA.publish(update);

		verify(brokerB, timeout(DELIVERY_WAIT_SECONDS * 1000)).convertAndSend(DESTINATION, update);
		verify(brokerA, after(500).never()).convertAndSend(eq(DESTINATION), (Object) any());
	}

	@Test
	@DisplayName("구독 연결이 끊겨도 다시 구독해 이후 좋아요를 받는다 (끊긴 동안의 것은 재전송하지 않는다)")
	void resubscribesAfterSubscriptionConnectionIsKilled() {
		killPubSubConnections();

		// Updates published while unsubscribed are lost, so keep publishing until one lands.
		await().atMost(RESUBSCRIBE_WAIT_SECONDS, TimeUnit.SECONDS)
				.pollInterval(500, TimeUnit.MILLISECONDS)
				.untilAsserted(() -> {
					relayA.publish(update(2L));
					verify(brokerB, timeout(200).atLeastOnce()).convertAndSend(DESTINATION, update(2L));
				});
	}

	private static LikeResponse update(long version) {
		return new LikeResponse(10L, Like.TargetType.POST, LikeResponse.LikeAction.LIKED, 3, 0, version);
	}

	private RedisMessageListenerContainer container(LikeRelaySubscriber subscriber) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(factory);
		container.addMessageListener(subscriber, new ChannelTopic(CHANNEL));
		container.afterPropertiesSet();
		container.start();
		return container;
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
