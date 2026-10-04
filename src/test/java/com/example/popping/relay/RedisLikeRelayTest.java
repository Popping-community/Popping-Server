package com.example.popping.relay;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.example.popping.domain.Like;
import com.example.popping.dto.LikeResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RedisLikeRelayTest {

	private static final String CHANNEL = "test-like-channel";
	private static final long WAIT_SECONDS = 5;
	private static final LikeResponse UPDATE =
			new LikeResponse(10L, Like.TargetType.POST, LikeResponse.LikeAction.LIKED, 3, 1, 7L);

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
	private RedisLikeRelay relay;

	@AfterEach
	void tearDown() {
		if (relay != null) {
			relay.destroy();
		}
	}

	@Test
	@DisplayName("자기 origin과 좋아요 상태(버전 포함)를 담아 채널에 발행한다")
	void publish_sendsUpdateWithOrigin() throws Exception {
		relay = new RedisLikeRelay(redis, objectMapper, "me", CHANNEL, meters);

		relay.publish(UPDATE);

		ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
		verify(redis, timeout(WAIT_SECONDS * 1000)).convertAndSend(eq(CHANNEL), payload.capture());
		assertThat(objectMapper.readValue(payload.getValue(), LikeRelayMessage.class))
				.isEqualTo(new LikeRelayMessage("me", UPDATE));
		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> count("sent") == 1);
	}

	@Test
	@DisplayName("Redis가 멈춰 있어도 호출자는 기다리지 않는다")
	void publish_doesNotBlockCallerWhileRedisHangs() throws Exception {
		CountDownLatch inPublish = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			inPublish.countDown();
			release.await();
			return 1L;
		}).when(redis).convertAndSend(anyString(), anyString());
		relay = new RedisLikeRelay(redis, objectMapper, "me", CHANNEL, meters);

		long started = System.nanoTime();
		relay.publish(UPDATE);
		relay.publish(UPDATE);
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

		// The caller is back while the publish it triggered is still stuck in Redis.
		assertThat(inPublish.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
		assertThat(release.getCount()).isEqualTo(1);
		assertThat(elapsedMillis).isLessThan(500);
		release.countDown();
		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> count("sent") == 2);
	}

	@Test
	@DisplayName("큐가 가득 차면 버리고 dropped로 센다")
	void publish_dropsWhenQueueFull() throws Exception {
		CountDownLatch inPublish = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			inPublish.countDown();
			release.await();
			return 1L;
		}).when(redis).convertAndSend(anyString(), anyString());
		relay = new RedisLikeRelay(redis, objectMapper, "me", CHANNEL, meters, 1);

		relay.publish(UPDATE);
		assertThat(inPublish.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
		relay.publish(UPDATE);
		relay.publish(UPDATE);

		assertThat(count("dropped")).isEqualTo(1);
		release.countDown();
		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> count("sent") == 2);
	}

	@Test
	@DisplayName("발행 실패는 호출자에게 전파되지 않고 failed로 센다")
	void publish_isolatesRedisFailure() {
		doThrow(new RedisConnectionFailureException("down")).when(redis).convertAndSend(anyString(), anyString());
		relay = new RedisLikeRelay(redis, objectMapper, "me", CHANNEL, meters);

		assertDoesNotThrow(() -> relay.publish(UPDATE));

		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> count("failed") == 1);
	}

	@Test
	@DisplayName("종료 후 호출은 예외 없이 dropped로 센다, null은 무시한다")
	void publish_afterShutdownOrNull_doesNotThrow() {
		relay = new RedisLikeRelay(redis, objectMapper, "me", CHANNEL, meters);
		relay.destroy();

		assertDoesNotThrow(() -> relay.publish(UPDATE));
		assertDoesNotThrow(() -> relay.publish(null));

		assertThat(count("dropped")).isEqualTo(1);
		verifyNoInteractions(redis);
	}

	private double count(String result) {
		var counter = meters.find(RedisLikeRelay.METRIC).tag("result", result).counter();
		return counter == null ? 0 : counter.count();
	}
}
