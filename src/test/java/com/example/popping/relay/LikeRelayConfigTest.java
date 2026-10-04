package com.example.popping.relay;

import java.net.ConnectException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LikeRelayConfigTest {

	/** Nothing listens here, so a redis-mode context cannot subscribe. */
	private static final int UNREACHABLE_REDIS_PORT = 6390;

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(LikeRelayConfig.class)
			.withBean(SimpMessagingTemplate.class, () -> mock(SimpMessagingTemplate.class))
			.withBean(ObjectMapper.class, ObjectMapper::new)
			.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
			.withBean(RelayConsumer.class);

	@Test
	@DisplayName("설정이 없으면 local: NoOp 중계만 있고 Redis 구독 빈은 없다")
	void defaultIsLocal() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(LikeRelay.class)).isInstanceOf(NoOpLikeRelay.class);
			assertThat(context).doesNotHaveBean(LikeRelaySubscriber.class);
			assertThat(context).doesNotHaveBean(RedisMessageListenerContainer.class);
		});
	}

	@Test
	@DisplayName("알 수 없는 값이면 중계 빈이 없어 기동에 실패한다")
	void unknownValueFailsStartup() {
		runner.withPropertyValues("app.websocket.relay=redsi").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
					.hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class)
					.rootCause().hasMessageContaining(LikeRelay.class.getName());
		});
	}

	@Test
	@DisplayName("redis 모드에서 Redis에 연결할 수 없으면 기동에 실패한다")
	void redisModeWithoutRedisFailsStartup() {
		LettuceConnectionFactory unreachable = new LettuceConnectionFactory("localhost", UNREACHABLE_REDIS_PORT);
		runner.withPropertyValues("app.websocket.relay=redis")
				.withBean(RedisConnectionFactory.class, () -> unreachable)
				.withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(unreachable))
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasMessageContaining("likeRelayListenerContainer")
							.hasRootCauseInstanceOf(ConnectException.class);
				});
	}

	@Test
	@DisplayName("구독 실행기 큐가 가득 차면 예외 대신 dropped로 센다")
	void listenerExecutor_countsDropsWhenFull() throws Exception {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		ThreadPoolTaskExecutor executor = LikeRelayConfig.listenerExecutor(meters, 1);
		executor.initialize();
		CountDownLatch running = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try {
			executor.execute(() -> {
				running.countDown();
				awaitQuietly(release);
			});
			assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
			executor.execute(() -> { });
			executor.execute(() -> { });

			assertThat(meters.find(LikeRelaySubscriber.METRIC).tag("result", "dropped").counter().count())
					.isEqualTo(1);
		} finally {
			release.countDown();
			executor.shutdown();
		}
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Stands in for the controller, which cannot start without a relay. */
	static class RelayConsumer {
		RelayConsumer(LikeRelay relay) {
		}
	}
}
