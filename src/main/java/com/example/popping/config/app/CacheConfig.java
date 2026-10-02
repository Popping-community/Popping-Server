package com.example.popping.config.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.Assert;

import com.github.benmanes.caffeine.cache.Caffeine;

@Configuration
@Profile("!nocache")
public class CacheConfig {

	public static final String BOARD_FIRST_PAGE_CACHE = "boardFirstPage";
	public static final String POST_DETAIL_CACHE = "postDetail";
	public static final String COMMENT_FIRST_PAGE_CACHE = "commentFirstPage";

	@Bean
	public CacheManager cacheManager(
			@Value("${app.cache.comment-first-page.enabled:true}") boolean commentFirstPageEnabled,
			@Value("${app.cache.comment-first-page.ttl-seconds:5}") long commentFirstPageTtlSeconds,
			@Value("${app.cache.post-detail.enabled:false}") boolean postDetailEnabled) {
		Assert.isTrue(commentFirstPageTtlSeconds > 0, "Comment cache TTL must be positive");
		SimpleCacheManager cacheManager = new SimpleCacheManager();
		List<Cache> caches = new ArrayList<>(List.of(
				buildCache(BOARD_FIRST_PAGE_CACHE, 50, 5, TimeUnit.MINUTES)
		));
		// Local eviction cannot invalidate another JVM. Opt in only when stale detail is acceptable.
		if (postDetailEnabled) {
			caches.add(buildCache(POST_DETAIL_CACHE, 1000, 30, TimeUnit.MINUTES));
		}
		// Writers bypass this cache while their sticky-primary cookie is active.
		// Expiry bounds entry residence, not freshness relative to a lagging Replica.
		if (commentFirstPageEnabled) {
			caches.add(buildCache(COMMENT_FIRST_PAGE_CACHE, 500, commentFirstPageTtlSeconds, TimeUnit.SECONDS));
		}
		cacheManager.setCaches(caches);
		return cacheManager;
	}

	private CaffeineCache buildCache(String name, int maxSize, long expireAfter, TimeUnit unit) {
		return new CaffeineCache(name, Caffeine.newBuilder()
				.maximumSize(maxSize)
				.expireAfterWrite(expireAfter, unit)
				.recordStats()
				.build());
	}
}
