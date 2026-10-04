package com.example.popping.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import com.example.popping.cache.CacheInvalidationBroadcaster;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheEvictListenerTest {

	@Mock CacheManager cacheManager;
	@Mock Cache cache;
	@Mock CacheInvalidationBroadcaster broadcaster;

	@InjectMocks CacheEvictListener listener;

	@Test
	@DisplayName("이벤트 수신: 로컬 캐시를 evict한 뒤 다른 인스턴스에 전파한다")
	void onCacheEvict_evictsLocallyThenBroadcasts() {
		when(cacheManager.getCache("commentFirstPage")).thenReturn(cache);

		listener.onCacheEvict(new CacheEvictEvent("commentFirstPage", 10L));

		InOrder order = inOrder(cache, broadcaster);
		order.verify(cache).evict(10L);
		order.verify(broadcaster).broadcast("commentFirstPage", 10L);
	}

	@Test
	@DisplayName("이벤트 수신: 로컬에 캐시가 없어도 전파한다 (다른 인스턴스엔 있을 수 있음)")
	void onCacheEvict_cacheNull_stillBroadcasts() {
		when(cacheManager.getCache("postDetail")).thenReturn(null);

		assertDoesNotThrow(() -> listener.onCacheEvict(new CacheEvictEvent("postDetail", 10L)));

		verify(cache, never()).evict(any());
		verify(broadcaster).broadcast("postDetail", 10L);
	}

	@Test
	@DisplayName("이벤트 수신: key가 null이면 evict도 전파도 하지 않는다")
	void onCacheEvict_keyNull_doesNothing() {
		assertDoesNotThrow(() -> listener.onCacheEvict(new CacheEvictEvent("commentFirstPage", null)));

		verify(cache, never()).evict(any());
		verifyNoInteractions(broadcaster);
	}

	@Test
	@DisplayName("이벤트 수신: 다른 캐시 이름으로도 동작한다")
	void onCacheEvict_worksWithDifferentCacheNames() {
		when(cacheManager.getCache("boardFirstPage")).thenReturn(cache);

		listener.onCacheEvict(new CacheEvictEvent("boardFirstPage", 3L));

		verify(cache).evict(3L);
		verify(broadcaster).broadcast("boardFirstPage", 3L);
	}
}
