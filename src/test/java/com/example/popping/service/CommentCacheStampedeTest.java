package com.example.popping.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.popping.domain.Post;
import com.example.popping.repository.CommentRepository;
import com.example.popping.repository.CommentTreeRowView;
import com.example.popping.repository.LikeRepository;
import com.github.benmanes.caffeine.cache.Caffeine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentCacheStampedeTest {

    @Mock PostService postService;
    @Mock UserService userService;
    @Mock CommentRepository commentRepository;
    @Mock LikeRepository likeRepository;
    @Mock PasswordEncoder guestPasswordEncoder;

    CommentService commentService;

    @BeforeEach
    void setUp() {
        // 실제 Caffeine 캐시 인스턴스 사용 (Mock Cache로는 atomic loading 검증 불가)
        CaffeineCache caffeineCache = new CaffeineCache(
                "commentFirstPage",
                Caffeine.newBuilder().build()
        );
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(caffeineCache));
        cacheManager.afterPropertiesSet();

        // Callable을 그대로 실행하는 TransactionTemplate (트랜잭션 없이 callback만 위임)
        TransactionTemplate readOnlyTx = new TransactionTemplate() {
            @Override
            public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };

        commentService = new CommentService(
                postService, userService, commentRepository,
                likeRepository, guestPasswordEncoder, cacheManager, readOnlyTx
        );
    }

    @Test
    @DisplayName("캐시 스탬피드 방지: 동시 요청 50개가 몰려도 buildCommentPage(CTE 쿼리)는 1회만 실행된다")
    void cacheStampedePrevention_buildCommentPageCalledOnce() throws Exception {
        Long postId = 1L;
        int threadCount = 50;

        AtomicInteger buildCount = new AtomicInteger(0);

        Post post = mock(Post.class);
        when(post.getCommentCount()).thenReturn(1);
        when(postService.getPost(postId)).thenReturn(post);

        CommentTreeRowView row = mock(CommentTreeRowView.class);
        when(row.getId()).thenReturn(1L);
        when(row.getParentId()).thenReturn(null);
        when(row.getContent()).thenReturn("content");
        when(row.getDepth()).thenReturn(0);
        when(row.getUserId()).thenReturn(100L);
        when(row.getGuestNickname()).thenReturn(null);
        when(row.getLikeCount()).thenReturn(0);
        when(row.getDislikeCount()).thenReturn(0);

        when(commentRepository.findPagedCommentTree(any(), anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    buildCount.incrementAndGet();
                    Thread.sleep(30); // CTE 쿼리 지연 시뮬레이션
                    return List.of(row);
                });

        when(userService.getUserIdToNicknameMap(Set.of(100L)))
                .thenReturn(Map.of(100L, "nick"));
        when(commentRepository.findLikeCountsByIds(any()))
                .thenReturn(List.of());

        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    commentService.getCommentPage(postId, 0, null, null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
        }

        ready.await();   // 50개 스레드 전부 집결
        start.countDown(); // 동시 출발

        for (Future<?> f : futures) f.get();
        executor.shutdown();

        assertEquals(1, buildCount.get(), "스탬피드 방지 실패: CTE 쿼리가 " + buildCount.get() + "회 실행됨");
    }
}
