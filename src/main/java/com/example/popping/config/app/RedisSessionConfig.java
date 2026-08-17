package com.example.popping.config.app;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.SessionRepositoryFilter;

import jakarta.servlet.DispatcherType;

/**
 * Stores HTTP sessions in Redis, but only where an environment asks for it.
 *
 * <p>Spring Boot decides the session store from the classpath alone: with
 * spring-session-data-redis on it, every environment switches to Redis and there is no
 * property to opt out - {@code spring.session.store-type} was removed in Boot 3.x, so
 * setting it to {@code none} does nothing. Running without a Redis to talk to therefore
 * starts the app fine and then fails every request that touches a session with a 500,
 * which is what {@code ./gradlew bootRun} does on a machine with no Redis.
 *
 * <p>{@code SessionAutoConfiguration} is excluded in application.yml so that decision is
 * ours to make, and this class makes it: sessions live in Redis when
 * {@code app.session.store=redis}, and stay in Tomcat's memory otherwise.
 *
 * <p>{@code @EnableRedisHttpSession} is the non-indexed repository, matching what the
 * auto-configuration used before this class existed - one key per session under
 * {@code spring:session:sessions:}, no expiration index. It publishes no expiration
 * events and has no {@code findByPrincipalName}; needing either means moving to
 * {@code @EnableRedisIndexedHttpSession}, which changes the key layout and wants Redis
 * keyspace notifications.
 *
 * <p>What the exclusion costs: the {@code spring.session.*} properties no longer reach
 * the repository, so session timeout is the annotation's own 30-minute default rather
 * than {@code server.servlet.session.timeout}. That property still binds and still
 * governs Tomcat, so setting it later would move Tomcat's timeout while leaving Redis at
 * 30 minutes. Both are 30 minutes today, which is why this is a note and not a change.
 */
@Configuration
@ConditionalOnProperty(name = "app.session.store", havingValue = "redis")
@EnableRedisHttpSession
public class RedisSessionConfig {

    /**
     * Registers the session filter for the same dispatcher types Boot used to register it
     * for.
     *
     * <p>Left to Boot's ordinary filter-bean registration, {@code SessionRepositoryFilter}
     * would run on {@code REQUEST} only, while {@code SessionAutoConfiguration} registered
     * it for {@code REQUEST}, {@code ASYNC} and {@code ERROR}. On the dispatches it no
     * longer covers, {@code request.getSession()} falls through to Tomcat's own session
     * instead of the one in Redis - and SockJS transports dispatch asynchronously, so
     * {@code ASYNC} is not hypothetical here.
     */
    @Bean
    FilterRegistrationBean<SessionRepositoryFilter<?>> sessionRepositoryFilterRegistration(
            SessionRepositoryFilter<?> sessionRepositoryFilter) {
        FilterRegistrationBean<SessionRepositoryFilter<?>> registration =
                new FilterRegistrationBean<>(sessionRepositoryFilter);
        registration.setDispatcherTypes(
                DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER);
        return registration;
    }
}
