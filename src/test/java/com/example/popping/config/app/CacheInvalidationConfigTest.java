package com.example.popping.config.app;

import java.net.ConnectException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.example.popping.cache.CacheInvalidationBroadcaster;
import com.example.popping.cache.CacheInvalidationSubscriber;
import com.example.popping.cache.NoOpCacheInvalidationBroadcaster;
import com.example.popping.event.CacheEvictListener;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class CacheInvalidationConfigTest {

	/** Nothing listens here, so a redis-mode context cannot subscribe. */
	private static final int UNREACHABLE_REDIS_PORT = 6390;

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(CacheInvalidationConfig.class)
			.withBean(CacheManager.class, ConcurrentMapCacheManager::new)
			.withBean(ObjectMapper.class, ObjectMapper::new)
			.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
			.withBean(CacheEvictListener.class);

	@Test
	@DisplayName("설정이 없으면 local: NoOp 발행기만 있고 무효화용 Redis 빈은 없다")
	void defaultIsLocal() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(CacheInvalidationBroadcaster.class))
					.isInstanceOf(NoOpCacheInvalidationBroadcaster.class);
			assertThat(context).doesNotHaveBean(CacheInvalidationSubscriber.class);
			assertThat(context).doesNotHaveBean(RedisMessageListenerContainer.class);
		});
	}

	@Test
	@DisplayName("local을 명시해도 같다")
	void explicitLocal() {
		runner.withPropertyValues("app.cache.invalidation=local").run(context -> {
			assertThat(context.getBean(CacheInvalidationBroadcaster.class))
					.isInstanceOf(NoOpCacheInvalidationBroadcaster.class);
			assertThat(context).doesNotHaveBean(RedisMessageListenerContainer.class);
		});
	}

	@Test
	@DisplayName("알 수 없는 값이면 발행기가 없어 기동에 실패한다 (오타가 조용히 local이 되지 않음)")
	void unknownValueFailsStartup() {
		runner.withPropertyValues("app.cache.invalidation=redsi").run(context -> {
			assertThat(context).hasFailed();
			// Fails for want of a broadcaster, not because the redis branch half-matched.
			assertThat(context.getStartupFailure())
					.hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class)
					.rootCause().hasMessageContaining(CacheInvalidationBroadcaster.class.getName());
		});
	}

	@Test
	@DisplayName("redis 모드에서 Redis에 연결할 수 없으면 기동에 실패한다 (결정 B1)")
	void redisModeWithoutRedisFailsStartup() {
		LettuceConnectionFactory unreachable = new LettuceConnectionFactory("localhost", UNREACHABLE_REDIS_PORT);
		runner.withPropertyValues("app.cache.invalidation=redis")
				.withBean(RedisConnectionFactory.class, () -> unreachable)
				.withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(unreachable))
				.run(context -> {
					assertThat(context).hasFailed();
					// Fails because the subscription could not be made, not for some wiring reason.
					assertThat(context.getStartupFailure())
							.hasMessageContaining("cacheInvalidationListenerContainer")
							.hasRootCauseInstanceOf(ConnectException.class);
				});
	}
}
