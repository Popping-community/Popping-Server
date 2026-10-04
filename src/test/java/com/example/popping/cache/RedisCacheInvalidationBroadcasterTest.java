package com.example.popping.cache;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RedisCacheInvalidationBroadcasterTest {

	private static final String CHANNEL = "test-channel";
	private static final long WAIT_SECONDS = 5;

	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final InstanceId instanceId = new InstanceId("me");
	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
	private RedisCacheInvalidationBroadcaster broadcaster;

	@AfterEach
	void tearDown() {
		if (broadcaster != null) {
			broadcaster.destroy();
		}
	}

	@Test
	@DisplayName("메시지에 캐시 이름, Long 키, 자기 origin을 담아 채널에 발행한다")
	void broadcast_publishesMessageWithOrigin() throws Exception {
		broadcaster = new RedisCacheInvalidationBroadcaster(redis, objectMapper, instanceId, CHANNEL, meters);

		broadcaster.broadcast("boardFirstPage", 3L);

		ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
		verify(redis, timeout(WAIT_SECONDS * 1000)).convertAndSend(eq(CHANNEL), payload.capture());
		CacheInvalidationMessage sent = objectMapper.readValue(payload.getValue(), CacheInvalidationMessage.class);
		assertThat(sent).isEqualTo(new CacheInvalidationMessage("boardFirstPage", 3L, "me"));
		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> count("sent") == 1);
	}

	@Test
	@DisplayName("Redis가 멈춰 있어도 호출자는 기다리지 않고 바로 돌아온다 (AC4)")
	void broadcast_doesNotBlockCallerWhileRedisHangs() throws Exception {
		CountDownLatch inPublish = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			inPublish.countDown();
			release.await();
			return 1L;
		}).when(redis).convertAndSend(anyString(), anyString());
		broadcaster = new RedisCacheInvalidationBroadcaster(redis, objectMapper, instanceId, CHANNEL, meters);

		long started = System.nanoTime();
		broadcaster.broadcast("boardFirstPage", 1L);
		broadcaster.broadcast("boardFirstPage", 2L);
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

		// The caller is back while the publish it triggered is still stuck in Redis.
		assertThat(inPublish.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
		assertThat(release.getCount()).isEqualTo(1);
		assertThat(elapsedMillis).isLessThan(500);
		release.countDown();
		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> count("sent") == 2);
	}

	@Test
	@DisplayName("큐가 가득 차면 메시지를 버리고 dropped로 센다")
	void broadcast_dropsWhenQueueFull() throws Exception {
		CountDownLatch inPublish = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			inPublish.countDown();
			release.await();
			return 1L;
		}).when(redis).convertAndSend(anyString(), anyString());
		broadcaster = new RedisCacheInvalidationBroadcaster(redis, objectMapper, instanceId, CHANNEL, meters, 1);

		broadcaster.broadcast("boardFirstPage", 1L);
		assertThat(inPublish.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
		broadcaster.broadcast("boardFirstPage", 2L);
		broadcaster.broadcast("boardFirstPage", 3L);

		assertThat(count("dropped")).isEqualTo(1);
		release.countDown();
		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> count("sent") == 2);
	}

	@Test
	@DisplayName("발행 실패는 호출자에게 전파되지 않고 failed로 센다")
	void broadcast_isolatesPublishFailure() {
		doThrow(new RedisConnectionFailureException("down")).when(redis).convertAndSend(anyString(), anyString());
		broadcaster = new RedisCacheInvalidationBroadcaster(redis, objectMapper, instanceId, CHANNEL, meters);

		assertDoesNotThrow(() -> broadcaster.broadcast("boardFirstPage", 1L));

		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> count("failed") == 1);
	}

	@Test
	@DisplayName("종료 후 호출은 예외 없이 dropped로 센다")
	void broadcast_afterShutdown_doesNotThrow() {
		broadcaster = new RedisCacheInvalidationBroadcaster(redis, objectMapper, instanceId, CHANNEL, meters);
		broadcaster.destroy();

		assertDoesNotThrow(() -> broadcaster.broadcast("boardFirstPage", 1L));

		assertThat(count("dropped")).isEqualTo(1);
		verifyNoInteractions(redis);
	}

	@Test
	@DisplayName("cacheName이나 key가 null이면 아무것도 하지 않는다")
	void broadcast_nullArguments_ignored() {
		broadcaster = new RedisCacheInvalidationBroadcaster(redis, objectMapper, instanceId, CHANNEL, meters);

		broadcaster.broadcast(null, 1L);
		broadcaster.broadcast("boardFirstPage", null);

		verifyNoInteractions(redis);
	}

	private double count(String result) {
		var counter = meters.find(RedisCacheInvalidationBroadcaster.METRIC).tag("result", result).counter();
		return counter == null ? 0 : counter.count();
	}
}
