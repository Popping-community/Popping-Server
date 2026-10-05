package com.example.popping.config.app;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the timeouts on the Lettuce connection factory that the application files produce.
 * Spring Session and the like relay both use this one auto-configured factory, so a Redis
 * outage keeps failing fast instead of holding request threads for Lettuce's 60 s default.
 */
class RedisTimeoutConfigTest {

	@Test
	@DisplayName("Redis 연결 팩토리의 명령 제한시간은 2초, 연결 제한시간은 1초다")
	void redisTimeoutsAreShort() {
		new ApplicationContextRunner()
				.withInitializer(new ConfigDataApplicationContextInitializer())
				.withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
				.run(context -> {
					LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
					assertThat(factory.getClientConfiguration().getCommandTimeout())
							.isEqualTo(Duration.ofSeconds(2));
					assertThat(factory.getClientConfiguration().getClientOptions().orElseThrow()
							.getSocketOptions().getConnectTimeout())
							.isEqualTo(Duration.ofSeconds(1));
				});
	}
}
