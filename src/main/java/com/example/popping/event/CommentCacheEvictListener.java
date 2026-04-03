package com.example.popping.event;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CommentCacheEvictListener {

    private static final String COMMENT_FIRST_PAGE_CACHE = "commentFirstPage";

    private final CacheManager cacheManager;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommentChange(CommentCacheEvictEvent event) {
        Cache cache = cacheManager.getCache(COMMENT_FIRST_PAGE_CACHE);
        if (cache == null || event.postId() == null) return;
        cache.evict(event.postId());
    }
}
