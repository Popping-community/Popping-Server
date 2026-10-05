package com.example.popping.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.example.popping.controller.api.LikeTestApiController;
import com.example.popping.relay.LikeBroadcaster;
import com.example.popping.service.LikeService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LikeTestApiToggleTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
			.withBean(LikeService.class, () -> mock(LikeService.class))
			.withBean(LikeBroadcaster.class, () -> mock(LikeBroadcaster.class))
			.withUserConfiguration(LikeTestApiController.class);

	@Test
	@DisplayName("설정이 없으면 HTTP 좋아요 테스트 API는 등록되지 않는다")
	void offByDefault() {
		runner.run(context -> assertThat(context).doesNotHaveBean(LikeTestApiController.class));
	}

	@Test
	@DisplayName("app.test-api.likes.enabled=true일 때만 등록된다")
	void onWhenEnabled() {
		runner.withPropertyValues("app.test-api.likes.enabled=true")
				.run(context -> assertThat(context).hasSingleBean(LikeTestApiController.class));
	}
}
