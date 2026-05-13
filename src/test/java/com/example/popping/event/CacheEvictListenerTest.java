package com.example.popping.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheEvictListenerTest {

	@Mock CacheManager cacheManager;
	@Mock Cache cache;

	@InjectMocks CacheEvictListener listener;

	@Test
	@DisplayName("이벤트 수신: 해당 cacheName과 key로 캐시를 evict한다")
	void onCacheEvict_evictsKeyFromNamedCache() {
		when(cacheManager.getCache("commentFirstPage")).thenReturn(cache);

		listener.onCacheEvict(new CacheEvictEvent("commentFirstPage", 10L));

		verify(cache).evict(10L);
	}

	@Test
	@DisplayName("이벤트 수신: 캐시가 null이면 evict를 시도하지 않는다")
	void onCacheEvict_cacheNull_doesNothing() {
		when(cacheManager.getCache("commentFirstPage")).thenReturn(null);

		assertDoesNotThrow(() -> listener.onCacheEvict(new CacheEvictEvent("commentFirstPage", 10L)));
		verify(cache, never()).evict(any());
	}

	@Test
	@DisplayName("이벤트 수신: key가 null이면 evict를 시도하지 않는다")
	void onCacheEvict_keyNull_doesNothing() {
		when(cacheManager.getCache("commentFirstPage")).thenReturn(cache);

		assertDoesNotThrow(() -> listener.onCacheEvict(new CacheEvictEvent("commentFirstPage", null)));
		verify(cache, never()).evict(any());
	}

	@Test
	@DisplayName("이벤트 수신: 다른 캐시 이름으로도 동작한다")
	void onCacheEvict_worksWithDifferentCacheNames() {
		when(cacheManager.getCache("boardFirstPage")).thenReturn(cache);

		listener.onCacheEvict(new CacheEvictEvent("boardFirstPage", "free"));

		verify(cache).evict("free");
	}
}
