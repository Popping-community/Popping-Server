package com.example.popping.config.app;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.github.benmanes.caffeine.cache.Caffeine;

@Configuration
@Profile("!nocache")
public class CacheConfig {

	public static final String BOARD_FIRST_PAGE_CACHE = "boardFirstPage";
	public static final String POST_DETAIL_CACHE = "postDetail";
	public static final String COMMENT_FIRST_PAGE_CACHE = "commentFirstPage";

	@Bean
	public CacheManager cacheManager() {
		SimpleCacheManager cacheManager = new SimpleCacheManager();
		cacheManager.setCaches(List.of(
				buildCache(BOARD_FIRST_PAGE_CACHE, 50, 5),   // TODO: used by PostService (Story 2-7)
				buildCache(POST_DETAIL_CACHE, 1000, 30),     // TODO: used by PostService (Story 3)
				buildCache(COMMENT_FIRST_PAGE_CACHE, 500, 10)
		));
		return cacheManager;
	}

	private CaffeineCache buildCache(String name, int maxSize, int expireMinutes) {
		return new CaffeineCache(name, Caffeine.newBuilder()
				.maximumSize(maxSize)
				.expireAfterWrite(expireMinutes, TimeUnit.MINUTES)
				.recordStats()
				.build());
	}
}
