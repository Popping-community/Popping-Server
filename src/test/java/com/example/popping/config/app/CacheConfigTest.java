package com.example.popping.config.app;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;

import static org.assertj.core.api.Assertions.assertThat;

class CacheConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(CacheConfig.class);

	@Test
	void explicitOffDisablesCommentPagesButKeepsOtherCaches() {
		contextRunner.withPropertyValues("app.cache.comment-first-page.enabled=false", "app.cache.post-detail.enabled=true").run(context -> {
			CacheManager manager = context.getBean(CacheManager.class);
			assertThat(manager.getCache(CacheConfig.COMMENT_FIRST_PAGE_CACHE)).isNull();
			assertThat(manager.getCacheNames()).containsExactlyInAnyOrder(CacheConfig.POST_DETAIL_CACHE);
			manager.getCache(CacheConfig.POST_DETAIL_CACHE).put(1L, "post");
			assertThat(manager.getCache(CacheConfig.POST_DETAIL_CACHE).get(1L, String.class))
					.isEqualTo("post");
		});
	}

	@Test
	void defaultCommentCacheUsesFiveSecondExpiry() {
		contextRunner.run(context -> {
					CacheManager manager = context.getBean(CacheManager.class);
					// The board first page is not cached; only the comment page is by default.
					assertThat(manager.getCacheNames()).containsExactly(CacheConfig.COMMENT_FIRST_PAGE_CACHE);
					manager.getCache(CacheConfig.COMMENT_FIRST_PAGE_CACHE).put(1L, "page");
					assertThat(manager.getCache(CacheConfig.COMMENT_FIRST_PAGE_CACHE).get(1L, String.class))
							.isEqualTo("page");
					assertExpiry(manager, CacheConfig.COMMENT_FIRST_PAGE_CACHE, 5);
					assertThat(manager.getCache(CacheConfig.POST_DETAIL_CACHE)).isNull();
				});
	}

	@Test
	void commentTtlCanBeConfiguredWithoutChangingOtherCaches() {
		contextRunner.withPropertyValues("app.cache.comment-first-page.enabled=true",
				"app.cache.comment-first-page.ttl-seconds=2", "app.cache.post-detail.enabled=true").run(context -> {
			CacheManager manager = context.getBean(CacheManager.class);
			assertExpiry(manager, CacheConfig.COMMENT_FIRST_PAGE_CACHE, 2);
			assertExpiry(manager, CacheConfig.POST_DETAIL_CACHE, 1800);
		});
	}

	@Test
	void bothOptionalCachesOffLeavesNoCacheRegistered() {
		contextRunner.withPropertyValues("app.cache.comment-first-page.enabled=false",
				"app.cache.post-detail.enabled=false").run(context -> {
			CacheManager manager = context.getBean(CacheManager.class);
			assertThat(manager.getCacheNames()).isEmpty();
			assertThat(manager.getCache(CacheConfig.COMMENT_FIRST_PAGE_CACHE)).isNull();
			assertThat(manager.getCache(CacheConfig.POST_DETAIL_CACHE)).isNull();
		});
	}

	@Test
	void invalidCommentTtlFailsAtStartup() {
		contextRunner.withPropertyValues("app.cache.comment-first-page.ttl-seconds=0")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void explicitPostOffKeepsCommentCache() {
		contextRunner.withPropertyValues("app.cache.post-detail.enabled=false").run(context -> {
			CacheManager manager = context.getBean(CacheManager.class);
			assertThat(manager.getCache(CacheConfig.POST_DETAIL_CACHE)).isNull();
			assertExpiry(manager, CacheConfig.COMMENT_FIRST_PAGE_CACHE, 5);
		});
	}

	private void assertExpiry(CacheManager manager, String name, long seconds) {
		CaffeineCache cache = (CaffeineCache) manager.getCache(name);
		assertThat(cache.getNativeCache().policy().expireAfterWrite().orElseThrow()
				.getExpiresAfter(TimeUnit.SECONDS)).isEqualTo(seconds);
	}
}
