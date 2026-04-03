package com.example.popping.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.popping.event.CommentCacheEvictEvent;

import com.example.popping.domain.Comment;
import com.example.popping.domain.Post;
import com.example.popping.domain.User;
import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.CommentPageResponse;
import com.example.popping.dto.MemberCommentCreateRequest;
import com.example.popping.repository.CommentRepository;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Nested
@MockitoSettings(strictness = Strictness.LENIENT)
class CacheTests {

    @Mock CacheManager cacheManager;
    @Mock Cache cache;
    @Mock TransactionTemplate readOnlyTx;
    @Mock ApplicationEventPublisher eventPublisher;

    @Mock PostService postService;
    @Mock UserService userService;
    @Mock CommentRepository commentRepository;
    @Mock PasswordEncoder passwordEncoder;

    @InjectMocks CommentService commentService;

    private final Map<Object, Object> cacheStore = new ConcurrentHashMap<>();

    @BeforeEach
    void setUpCache() {
        cacheStore.clear();

        when(cacheManager.getCache(anyString())).thenReturn(cache);

        when(cache.get(any(), any(java.util.concurrent.Callable.class))).thenAnswer(inv -> {
            Object key = inv.getArgument(0);
            if (cacheStore.containsKey(key)) {
                return cacheStore.get(key);
            }
            java.util.concurrent.Callable<?> loader = inv.getArgument(1);
            Object value;
            try {
                value = loader.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            cacheStore.put(key, value);
            return value;
        });

        when(readOnlyTx.execute(any())).thenAnswer(inv -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });

        doAnswer(inv -> {
            Object key = inv.getArgument(0);
            Object value = inv.getArgument(1);
            cacheStore.put(key, value);
            return null;
        }).when(cache).put(any(), any());

        doAnswer(inv -> {
            Object key = inv.getArgument(0);
            cacheStore.remove(key);
            return null;
        }).when(cache).evict(any());

    }

    @Test
    @DisplayName("댓글 첫 페이지 조회: 캐시가 존재하면 DB 조회 없이 캐시 데이터를 반환한다")
    void getCommentFirstPage_cacheHit() {

        // given
        Long postId = 10L;
        Post post = mock(Post.class);
        when(postService.getPost(postId)).thenReturn(post);

        when(commentRepository.findPagedCommentTree(postId, CommentService.COMMENTS_SIZE, 0))
                .thenReturn(List.of());

        // when
        commentService.getCommentPage(postId, 0, null, null);
        commentService.getCommentPage(postId, 0, null, null);

        // then
        verify(commentRepository, times(1))
                .findPagedCommentTree(postId, CommentService.COMMENTS_SIZE, 0);
    }

    @Test
    @DisplayName("댓글 생성: 트랜잭션 커밋 후 캐시 무효화를 위해 CommentCacheEvictEvent를 발행한다")
    void createComment_shouldPublishCacheEvictEvent() {

        // given
        Long postId = 10L;
        Post post = mock(Post.class);
        when(postService.getPost(postId)).thenReturn(post);

        MemberCommentCreateRequest dto = new MemberCommentCreateRequest("hello");
        UserPrincipal principal = principal(1L);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);

        Comment saved = commentWithId(100L);
        when(commentRepository.save(any(Comment.class))).thenReturn(saved);

        // when
        commentService.createMemberComment(postId, dto, principal, null);

        // then: evict는 AFTER_COMMIT 리스너에서 실행되므로, 이벤트 발행 여부만 검증한다
        verify(eventPublisher).publishEvent(any(CommentCacheEvictEvent.class));
        verify(cache, never()).evict(any());
    }

    @Test
    @DisplayName("댓글 좋아요 변경: 첫 페이지 캐시를 무효화하지 않는다 (반응 수치는 매 요청마다 fresh 조회)")
    void updateLikeCount_shouldNotEvictFirstPageCache() {

        // given
        Long postId = 10L;
        Long commentId = 1L;

        Post post = mock(Post.class);
        when(postService.getPost(postId)).thenReturn(post);

        when(commentRepository.findPagedCommentTree(postId, CommentService.COMMENTS_SIZE, 0))
                .thenReturn(List.of());

        // 캐시 채우기(1회 DB)
        commentService.getCommentPage(postId, 0, null, null);

        // when
        commentService.updateLikeCount(commentId, +1);

        // then - 좋아요는 캐시를 evict하지 않는다
        verify(cache, never()).evict(any());

        // 다시 조회해도 캐시가 유지되어 DB 재조회 없음
        commentService.getCommentPage(postId, 0, null, null);

        verify(commentRepository, times(1))
                .findPagedCommentTree(postId, CommentService.COMMENTS_SIZE, 0);
    }

    @Test
    @DisplayName("댓글 첫 페이지 캐시: 공통 데이터는 한 번만 조회하고 사용자/게스트 반응만 합성한다")
    void getCommentFirstPage_commonCache_thenMergeReactionByActor() {
        Long postId = 10L;
        Post post = mock(Post.class);
        when(postService.getPost(postId)).thenReturn(post);
        when(commentRepository.findPagedCommentTree(postId, CommentService.COMMENTS_SIZE, 0))
                .thenReturn(List.of());

        UserPrincipal user = principal(1L);

        commentService.getCommentPage(postId, 0, user, null);
        commentService.getCommentPage(postId, 0, user, null);

        commentService.getCommentPage(postId, 0, null, "guest-1");
        commentService.getCommentPage(postId, 0, null, "guest-1");

        verify(commentRepository, times(1))
                .findPagedCommentTree(postId, CommentService.COMMENTS_SIZE, 0);
    }

    private UserPrincipal principal(Long userId) {
        UserPrincipal p = mock(UserPrincipal.class);
        when(p.getUserId()).thenReturn(userId);
        return p;
    }

    private User userAuthOnly() {
        return mock(User.class);
    }

    private Comment commentWithId(Long id) {
        Comment c = mock(Comment.class);
        when(c.getId()).thenReturn(id);
        return c;
    }
}
