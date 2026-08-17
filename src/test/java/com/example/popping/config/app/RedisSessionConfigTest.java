package com.example.popping.config.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.session.SessionAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;

import jakarta.servlet.DispatcherType;

/**
 * Guards the opt-in that keeps sessions out of Redis unless an environment asks for it.
 *
 * <p>The configuration this replaced used spring.session.store-type, which Boot 3.x
 * removed - it was ignored in both directions, so sessions went to Redis everywhere. A
 * test that only checked the opt-in case would have passed against that broken config
 * too, which is why the default case asserts a SessionRepository is absent rather than
 * asserting anything about the store that is present.
 */
class RedisSessionConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
            .withUserConfiguration(RedisSessionConfig.class);

    @Test
    @DisplayName("app.session.store=redis 이면 Redis 세션 저장소가 등록된다")
    void redisStore_registersRedisSessionRepository() {
        runner.withPropertyValues("app.session.store=redis")
                .run(context -> assertThat(context).hasSingleBean(RedisSessionRepository.class));
    }

    @Test
    @DisplayName("app.session.store 가 없으면 세션 저장소를 전혀 등록하지 않는다")
    void noProperty_registersNoSessionRepository() {
        runner.run(context -> assertThat(context).doesNotHaveBean(SessionRepository.class));
    }

    @Test
    @DisplayName("app.session.store 가 redis 가 아니면 세션 저장소를 등록하지 않는다")
    void otherStore_registersNoSessionRepository() {
        runner.withPropertyValues("app.session.store=none")
                .run(context -> assertThat(context).doesNotHaveBean(SessionRepository.class));
    }

    @Test
    @DisplayName("Redis 세션 필터는 REQUEST/ASYNC/ERROR 모두에서 동작한다")
    void filterRegistration_coversAsyncAndErrorDispatches() {
        // Left to Boot's plain filter-bean registration this would be REQUEST only, and
        // the dispatches it missed would silently fall back to Tomcat's session. SockJS
        // transports dispatch asynchronously, so ASYNC is a live path here.
        runner.withPropertyValues("app.session.store=redis").run(context -> {
            FilterRegistrationBean<?> registration =
                    context.getBean(FilterRegistrationBean.class);
            assertThat(registration.getOrder()).isEqualTo(SessionRepositoryFilter.DEFAULT_ORDER);
            assertThat(registration).extracting("dispatcherTypes")
                    .asInstanceOf(InstanceOfAssertFactories.iterable(DispatcherType.class))
                    .containsExactlyInAnyOrder(
                            DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        });
    }

    @Test
    @DisplayName("application.yml 이 SessionAutoConfiguration 배제를 실제로 환경에 싣는다")
    void applicationYml_carriesTheExclusion() {
        // The three tests above never load application.yml, so they would all still pass
        // if the exclusion were deleted - and then the auto-configuration below would take
        // over and put every environment back on Redis. This is what ties them together.
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=classpath:/application.yml")
                .run(context -> assertThat(context.getEnvironment()
                        .getProperty("spring.autoconfigure.exclude[0]"))
                        .isEqualTo(SessionAutoConfiguration.class.getName()));
    }

    @Test
    @DisplayName("SessionAutoConfiguration 이 살아 있으면 opt-in 없이도 Redis 세션이 켜진다")
    void autoConfigurationAlone_switchesToRedisWithoutOptIn() {
        // The reason application.yml excludes it. If this ever stops holding, the
        // exclusion is no longer load-bearing and the comment there is wrong. The
        // auto-configuration only applies to servlet web applications, hence the
        // web runner rather than the plain one used above.
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        RedisAutoConfiguration.class, SessionAutoConfiguration.class))
                .run(context -> assertThat(context).hasSingleBean(SessionRepository.class));
    }
}
