package com.example.popping.event;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.example.popping.cache.CacheInvalidationBroadcaster;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CacheEvictListener {

	private final CacheManager cacheManager;
	private final CacheInvalidationBroadcaster broadcaster;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onCacheEvict(CacheEvictEvent event) {
		if (event.key() == null) {
			return;
		}
		Cache cache = cacheManager.getCache(event.cacheName());
		if (cache != null) {
			cache.evict(event.key());
		}
		// Broadcast even without a local cache: another instance may have this cache enabled.
		broadcaster.broadcast(event.cacheName(), event.key());
	}
}
