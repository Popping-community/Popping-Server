package com.example.popping.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.popping.event.CacheEvictEvent;

import com.example.popping.domain.Board;
import com.example.popping.domain.Comment;
import com.example.popping.domain.Post;
import com.example.popping.domain.User;
import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.MemberCommentCreateRequest;
import com.example.popping.dto.MemberPostUpdateRequest;
import com.example.popping.dto.PostListItemResponse;
import com.example.popping.dto.PostResponse;
import com.example.popping.dto.MemberPostCreateRequest;
import com.example.popping.repository.CommentRepository;
import com.example.popping.repository.LikeRepository;
import com.example.popping.repository.PostRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CacheTests {

    static void setUpCacheSimulation(CacheManager cacheManager, Cache cache,
                                     TransactionTemplate readOnlyTx,
                                     Map<Object, Object> cacheStore) {
        cacheStore.clear();

        when(cacheManager.getCache(anyString())).thenReturn(cache);

        when(cache.get(any(), any(Callable.class))).thenAnswer(inv -> {
            Object key = inv.getArgument(0);
            if (cacheStore.containsKey(key)) {
                return cacheStore.get(key);
            }
            Callable<?> loader = inv.getArgument(1);
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
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });

        doAnswer(inv -> {
            cacheStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(cache).put(any(), any());

        doAnswer(inv -> {
            cacheStore.remove(inv.getArgument(0));
            return null;
        }).when(cache).evict(any());
    }

    static UserPrincipal principal(Long userId) {
        UserPrincipal p = mock(UserPrincipal.class);
        when(p.getUserId()).thenReturn(userId);
        return p;
    }

    // ─── Comment First Page Cache ───

    @Nested
    @MockitoSettings(strictness = Strictness.LENIENT)
    class CommentFirstPageCacheTest {

        @Mock CacheManager cacheManager;
        @Mock Cache cache;
        @Mock TransactionTemplate readOnlyTx;
        @Mock ApplicationEventPublisher eventPublisher;
        @Mock PostService postService;
        @Mock UserService userService;
        @Mock CommentRepository commentRepository;
        @Mock PasswordEncoder passwordEncoder;
        @Mock GuestIdentifierService guestIdentifierService;

        @InjectMocks CommentService commentService;

        private final Map<Object, Object> cacheStore = new ConcurrentHashMap<>();

        @BeforeEach
        void setUp() {
            setUpCacheSimulation(cacheManager, cache, readOnlyTx, cacheStore);
        }

        @Test
        @DisplayName("댓글 첫 페이지 조회: 캐시가 존재하면 DB 조회 없이 캐시 데이터를 반환한다")
        void getCommentFirstPage_cacheHit() {
            Long postId = 10L;
            Post post = mock(Post.class);
            when(postService.getPost(postId)).thenReturn(post);
            when(commentRepository.findPagedCommentTree(postId, CommentService.COMMENTS_SIZE, 0))
                    .thenReturn(List.of());

            commentService.getCommentPage(postId, 0, null, null);
            commentService.getCommentPage(postId, 0, null, null);

            verify(commentRepository, times(1))
                    .findPagedCommentTree(postId, CommentService.COMMENTS_SIZE, 0);
        }

        @Test
        @DisplayName("댓글 생성: 트랜잭션 커밋 후 캐시 무효화를 위해 CacheEvictEvent를 발행한다")
        void createComment_shouldPublishCacheEvictEvent() {
            Long postId = 10L;
            Post post = mock(Post.class);
            when(postService.getPost(postId)).thenReturn(post);

            MemberCommentCreateRequest dto = new MemberCommentCreateRequest("hello");
            UserPrincipal principal = principal(1L);
            User user = mock(User.class);
            when(userService.getLoginUserById(1L)).thenReturn(user);

            Comment saved = mock(Comment.class);
            when(saved.getId()).thenReturn(100L);
            when(commentRepository.save(any(Comment.class))).thenReturn(saved);

            commentService.createMemberComment(postId, dto, principal, null);

            verify(eventPublisher, times(2)).publishEvent(any(CacheEvictEvent.class));
            verify(cache, never()).evict(any());
        }

        @Test
        @DisplayName("댓글 좋아요 변경: 첫 페이지 캐시를 무효화하지 않는다")
        void updateLikeCount_shouldNotEvictFirstPageCache() {
            Long postId = 10L;
            Post post = mock(Post.class);
            when(postService.getPost(postId)).thenReturn(post);
            when(commentRepository.findPagedCommentTree(postId, CommentService.COMMENTS_SIZE, 0))
                    .thenReturn(List.of());

            commentService.getCommentPage(postId, 0, null, null);
            commentService.updateLikeCount(1L, +1);

            verify(cache, never()).evict(any());

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
    }

    // ─── Board First Page Cache ───

    @Nested
    @MockitoSettings(strictness = Strictness.LENIENT)
    class BoardFirstPageCacheTest {

        @Mock CacheManager cacheManager;
        @Mock Cache cache;
        @Mock TransactionTemplate readOnlyTx;
        @Mock ApplicationEventPublisher eventPublisher;
        @Mock BoardService boardService;
        @Mock ImageService imageService;
        @Mock UserService userService;
        @Mock ViewCountService viewCountService;
        @Mock PasswordEncoder guestPasswordEncoder;
        @Mock PostRepository postRepository;
        @Mock LikeRepository likeRepository;

        @InjectMocks PostService postService;

        private final Map<Object, Object> cacheStore = new ConcurrentHashMap<>();
        private Board board;

        @BeforeEach
        void setUp() {
            setUpCacheSimulation(cacheManager, cache, readOnlyTx, cacheStore);
            board = mock(Board.class);
            when(board.getId()).thenReturn(1L);
            when(boardService.getBoard("free")).thenReturn(board);
        }

        @Test
        @DisplayName("게시판 첫 페이지 조회: 캐시가 존재하면 DB 조회 없이 캐시 데이터를 반환한다")
        void getBoardFirstPage_cacheHit() {
            when(postRepository.findPostListByBoard(board, PageRequest.of(0, 20)))
                    .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

            postService.getPostPage("free", 0, 20);
            postService.getPostPage("free", 0, 20);

            verify(postRepository, times(1))
                    .findPostListByBoard(board, PageRequest.of(0, 20));
        }

        @Test
        @DisplayName("게시판 두 번째 페이지 조회: 캐시를 사용하지 않고 매번 DB를 조회한다")
        void getBoardSecondPage_noCache() {
            when(postRepository.findPostListByBoard(board, PageRequest.of(1, 20)))
                    .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(1, 20), false));

            postService.getPostPage("free", 1, 20);
            postService.getPostPage("free", 1, 20);

            verify(postRepository, times(2))
                    .findPostListByBoard(board, PageRequest.of(1, 20));
        }

        @Test
        @DisplayName("게시글 생성: 게시판 첫 페이지 캐시 evict 이벤트를 발행한다")
        void createPost_shouldPublishBoardCacheEvictEvent() {
            MemberPostCreateRequest dto = new MemberPostCreateRequest("title", "content");
            UserPrincipal principal = principal(1L);
            User user = mock(User.class);
            when(userService.getLoginUserById(1L)).thenReturn(user);

            Post saved = mock(Post.class);
            when(saved.getId()).thenReturn(100L);
            when(postRepository.save(any(Post.class))).thenReturn(saved);

            postService.createMemberPost("free", dto, principal);

            verify(eventPublisher).publishEvent(any(CacheEvictEvent.class));
        }

        @Test
        @DisplayName("게시글 좋아요 변경: 게시판 첫 페이지 캐시를 무효화하지 않는다")
        void updateLikeCount_shouldNotEvictBoardCache() {
            when(postRepository.findPostListByBoard(board, PageRequest.of(0, 20)))
                    .thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

            postService.getPostPage("free", 0, 20);
            postService.updateLikeCount(1L, +1);

            verify(cache, never()).evict(any());
            verify(eventPublisher, never()).publishEvent(any(CacheEvictEvent.class));

            postService.getPostPage("free", 0, 20);
            verify(postRepository, times(1))
                    .findPostListByBoard(board, PageRequest.of(0, 20));
        }
    }

    // ─── Post Detail Cache ───

    @Nested
    @MockitoSettings(strictness = Strictness.LENIENT)
    class PostDetailCacheTest {

        @Mock CacheManager cacheManager;
        @Mock Cache cache;
        @Mock TransactionTemplate readOnlyTx;
        @Mock ApplicationEventPublisher eventPublisher;
        @Mock BoardService boardService;
        @Mock ImageService imageService;
        @Mock UserService userService;
        @Mock ViewCountService viewCountService;
        @Mock PasswordEncoder guestPasswordEncoder;
        @Mock PostRepository postRepository;
        @Mock LikeRepository likeRepository;

        @InjectMocks PostService postService;

        private final Map<Object, Object> cacheStore = new ConcurrentHashMap<>();
        private Post post;

        @BeforeEach
        void setUp() {
            setUpCacheSimulation(cacheManager, cache, readOnlyTx, cacheStore);

            User author = mock(User.class);
            when(author.getId()).thenReturn(1L);
            when(author.getNickname()).thenReturn("작성자");

            Board board = mock(Board.class);
            when(board.getName()).thenReturn("자유게시판");

            post = mock(Post.class);
            when(post.getId()).thenReturn(50L);
            when(post.getTitle()).thenReturn("제목");
            when(post.getContent()).thenReturn("내용");
            when(post.getAuthor()).thenReturn(author);
            when(post.getBoard()).thenReturn(board);
            when(post.getViewCount()).thenReturn(100L);

            when(postRepository.findById(50L)).thenReturn(Optional.of(post));

            when(likeRepository.findReactionForMember(any(), any(), any()))
                    .thenReturn(java.util.Collections.emptyList());
            when(likeRepository.findReactionForGuest(any(), any(), any()))
                    .thenReturn(java.util.Collections.emptyList());
        }

        @Test
        @DisplayName("게시글 상세 조회: 캐시가 존재하면 DB 조회 없이 캐시 데이터를 반환한다")
        void getPostDetail_cacheHit() {
            postService.getPostResponse(50L, null, null);
            postService.getPostResponse(50L, null, null);

            verify(postRepository, times(1)).findById(50L);
        }

        @Test
        @DisplayName("게시글 상세 조회: 개인 반응은 캐시와 별도로 매번 조회한다")
        void getPostDetail_personalReaction_alwaysFresh() {
            UserPrincipal member = principal(1L);

            postService.getPostResponse(50L, member, null);
            postService.getPostResponse(50L, member, null);

            verify(postRepository, times(1)).findById(50L);
            verify(likeRepository, times(2)).findReactionForMember(any(), any(), eq(1L));
        }

        @Test
        @DisplayName("게시글 상세 조회: 비회원과 회원 모두 공통 캐시를 공유한다")
        void getPostDetail_commonCache_sharedByActors() {
            UserPrincipal member = principal(1L);

            postService.getPostResponse(50L, member, null);
            postService.getPostResponse(50L, null, "guest-1");
            postService.getPostResponse(50L, null, null);

            verify(postRepository, times(1)).findById(50L);
        }

        @Test
        @DisplayName("게시글 상세 조회: pending 조회수가 캐시된 viewCount에 합산된다")
        void getPostDetail_mergesPendingViewCount() {
            when(viewCountService.getPendingCount(50L)).thenReturn(5L);

            PostResponse res = postService.getPostResponse(50L, null, null);

            assertThat(res.viewCount()).isEqualTo(105L);
        }

        @Test
        @DisplayName("게시글 수정: postDetail + boardFirstPage 캐시 evict 이벤트를 발행한다")
        void updatePost_shouldPublishEvictEvents() {
            User user = mock(User.class);
            when(userService.getLoginUserById(1L)).thenReturn(user);
            when(post.isAuthor(user)).thenReturn(true);

            postService.updatePost(50L,
                    new MemberPostUpdateRequest("new", "new"),
                    principal(1L));

            verify(eventPublisher, times(2)).publishEvent(any(CacheEvictEvent.class));
        }

        @Test
        @DisplayName("게시글 좋아요 변경: postDetail 캐시를 무효화하지 않는다")
        void updateLikeCount_shouldNotEvictPostDetailCache() {
            postService.getPostResponse(50L, null, null);
            postService.updateLikeCount(50L, +1);

            verify(cache, never()).evict(any());
            verify(eventPublisher, never()).publishEvent(any(CacheEvictEvent.class));

            postService.getPostResponse(50L, null, null);
            verify(postRepository, times(1)).findById(50L);
        }
    }
}
