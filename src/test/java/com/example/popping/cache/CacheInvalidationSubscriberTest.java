package com.example.popping.cache;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.connection.DefaultMessage;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class CacheInvalidationSubscriberTest {

	private static final byte[] CHANNEL = "test-channel".getBytes(StandardCharsets.UTF_8);

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
	private final ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager("boardFirstPage");
	private final CacheInvalidationSubscriber subscriber =
			new CacheInvalidationSubscriber(cacheManager, objectMapper, new InstanceId("me"), meters);
	private Cache cache;

	@BeforeEach
	void setUp() {
		cache = cacheManager.getCache("boardFirstPage");
		cache.put(3L, "page");
	}

	@Test
	@DisplayName("JSON을 거친 키가 Long으로 돌아와 원래 Long 키 엔트리를 지운다")
	void onMessage_evictsLongKeyAfterJsonRoundTrip() throws Exception {
		receive(objectMapper.writeValueAsString(new CacheInvalidationMessage("boardFirstPage", 3L, "other")));

		assertThat(cache.get(3L)).isNull();
		assertThat(count("evicted")).isEqualTo(1);
	}

	@Test
	@DisplayName("자기가 보낸 메시지는 무시한다")
	void onMessage_ignoresOwnMessage() throws Exception {
		receive(objectMapper.writeValueAsString(new CacheInvalidationMessage("boardFirstPage", 3L, "me")));

		assertThat(cache.get(3L)).isNotNull();
		assertThat(count("own")).isEqualTo(1);
	}

	@Test
	@DisplayName("이 인스턴스에 없는 캐시면 무시한다")
	void onMessage_unknownCache_ignored() throws Exception {
		ConcurrentMapCacheManager fixed = new ConcurrentMapCacheManager("boardFirstPage");
		CacheInvalidationSubscriber strict =
				new CacheInvalidationSubscriber(fixed, objectMapper, new InstanceId("me"), meters);

		strict.onMessage(message(objectMapper.writeValueAsString(
				new CacheInvalidationMessage("postDetail", 3L, "other"))), null);

		assertThat(fixed.getCacheNames()).containsExactly("boardFirstPage");
		assertThat(count("no_cache")).isEqualTo(1);
	}

	@Test
	@DisplayName("깨진 본문, JSON null, 필드 누락은 예외 없이 invalid로 센다")
	void onMessage_invalidBodies_counted() {
		receive("{not json");
		receive("null");
		receive("{\"cacheName\":\"boardFirstPage\",\"origin\":\"other\"}");

		assertThat(cache.get(3L)).isNotNull();
		assertThat(count("invalid")).isEqualTo(3);
	}

	private void receive(String body) {
		subscriber.onMessage(message(body), null);
	}

	private static DefaultMessage message(String body) {
		return new DefaultMessage(CHANNEL, body.getBytes(StandardCharsets.UTF_8));
	}

	private double count(String result) {
		var counter = meters.find(CacheInvalidationSubscriber.METRIC).tag("result", result).counter();
		return counter == null ? 0 : counter.count();
	}
}
