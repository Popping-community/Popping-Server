package com.example.popping.event;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CacheEvictListener {

	private final CacheManager cacheManager;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onCacheEvict(CacheEvictEvent event) {
		Cache cache = cacheManager.getCache(event.cacheName());
		if (cache == null || event.key() == null) {
			return;
		}
		cache.evict(event.key());
	}
}
