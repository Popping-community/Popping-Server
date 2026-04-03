package com.example.popping.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.popping.domain.Comment;
import com.example.popping.domain.Post;
import com.example.popping.domain.User;
import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.CommentPageResponse;
import com.example.popping.dto.CommentResponse;
import com.example.popping.dto.GuestCommentCreateRequest;
import com.example.popping.dto.MemberCommentCreateRequest;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.CommentRepository;
import com.example.popping.repository.CommentTreeRowView;
import com.example.popping.repository.LikeRepository;
import com.example.popping.repository.MyReactionView;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock PostService postService;
    @Mock UserService userService;
    @Mock CommentRepository commentRepository;
    @Mock LikeRepository likeRepository;
    @Mock PasswordEncoder guestPasswordEncoder;
    @Mock CacheManager cacheManager;
    @Mock Cache cache;
    @Mock TransactionTemplate readOnlyTx;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks CommentService commentService;

    @Test
    @DisplayName("회원 댓글 생성: Comment를 올바르게 생성하고 저장 후 commentCount를 증가시킨다")
    void createMemberComment_success() {

        // given
        Long postId = 10L;
        Long parentId = null;

        MemberCommentCreateRequest dto = new MemberCommentCreateRequest("hello");
        UserPrincipal principal = principal(1L);

        Post post = mock(Post.class);
        when(postService.getPost(postId)).thenReturn(post);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);

        Comment saved = commentWithId(100L);
        when(commentRepository.save(any(Comment.class))).thenReturn(saved);

        // when
        Long commentId = commentService.createMemberComment(postId, dto, principal, parentId);

        // then
        assertEquals(100L, commentId);

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(captor.capture());

        Comment captured = captor.getValue();
        assertEquals("hello", captured.getContent());
        assertSame(user, captured.getAuthor());
        assertSame(post, captured.getPost());
        assertNull(captured.getParent());
        assertEquals(0, captured.getDepth());

        verify(post).increaseCommentCount();
    }

    @Test
    @DisplayName("회원 대댓글 생성: 부모 댓글의 depth+1로 생성하고 저장 후 commentCount를 증가시킨다")
    void createMemberReply_success_depthPlusOne() {

        // given
        Long postId = 10L;
        Long parentId = 7L;

        MemberCommentCreateRequest dto = new MemberCommentCreateRequest("reply");
        UserPrincipal principal = principal(1L);

        Post post = mock(Post.class);
        when(postService.getPost(postId)).thenReturn(post);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);

        Comment parent = mock(Comment.class);
        when(commentRepository.findById(parentId)).thenReturn(Optional.of(parent));
        when(parent.getDepth()).thenReturn(2);

        Comment saved = commentWithId(101L);
        when(commentRepository.save(any(Comment.class))).thenReturn(saved);

        // when
        Long commentId = commentService.createMemberComment(postId, dto, principal, parentId);

        // then
        assertEquals(101L, commentId);

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(captor.capture());

        Comment captured = captor.getValue();
        assertEquals("reply", captured.getContent());
        assertSame(user, captured.getAuthor());
        assertSame(post, captured.getPost());
        assertSame(parent, captured.getParent());
        assertEquals(3, captured.getDepth()); // parent(2)+1

        verify(post).increaseCommentCount();
    }

    @Test
    @DisplayName("게스트 댓글 생성: 비밀번호를 encode하고 Comment를 생성 후 저장, commentCount를 증가시킨다")
    void createGuestComment_success() {

        // given
        Long postId = 10L;
        Long parentId = null;

        GuestCommentCreateRequest dto =
                new GuestCommentCreateRequest("hi", "guestNick", "1234");

        Post post = mock(Post.class);
        when(postService.getPost(postId)).thenReturn(post);

        when(guestPasswordEncoder.encode("1234")).thenReturn("ENC");

        Comment saved = commentWithId(200L);
        when(commentRepository.save(any(Comment.class))).thenReturn(saved);

        // when
        Long commentId = commentService.createGuestComment(postId, dto, parentId);

        // then
        assertEquals(200L, commentId);

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(captor.capture());

        Comment captured = captor.getValue();
        assertEquals("hi", captured.getContent());
        assertEquals("guestNick", captured.getGuestNickname());
        assertEquals("ENC", captured.getGuestPasswordHash());
        assertSame(post, captured.getPost());
        assertNull(captured.getParent());
        assertEquals(0, captured.getDepth());

        verify(guestPasswordEncoder).encode("1234");
        verify(post).increaseCommentCount();
    }

    @Test
    @DisplayName("댓글 삭제(회원): 작성자면 commentCount 감소 후 삭제한다")
    void deleteComment_success_owner() {

        // given
        Long commentId = 1L;
        UserPrincipal principal = principal(1L);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);

        Comment comment = mock(Comment.class);
        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));
        when(comment.isAuthor(user)).thenReturn(true);

        Post post = mock(Post.class);
        when(comment.getPost()).thenReturn(post);

        // when
        commentService.deleteComment(commentId, principal);

        // then
        verify(post).decreaseCommentCount();
        verify(commentRepository).delete(comment);
    }

    @Test
    @DisplayName("댓글 삭제(회원): 작성자가 아니면 ACCESS_DENIED 예외를 던진다")
    void deleteComment_fail_notOwner() {

        // given
        Long commentId = 1L;
        UserPrincipal principal = principal(1L);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);
        when(user.getLoginId()).thenReturn("login");

        Comment comment = mock(Comment.class);
        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));
        when(comment.isAuthor(user)).thenReturn(false);

        // when
        CustomAppException ex = assertThrows(
                CustomAppException.class,
                () -> commentService.deleteComment(commentId, principal)
        );

        // then
        assertEquals(ErrorType.ACCESS_DENIED, ex.getErrorType());
        verify(commentRepository, never()).delete(any());
    }

    @Test
    @DisplayName("댓글 삭제(게스트): 비밀번호가 일치하면 commentCount 감소 후 삭제한다")
    void deleteCommentAsGuest_success_passwordMatch() {

        // given
        Long commentId = 2L;

        Comment comment = mock(Comment.class);
        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));
        when(comment.isGuest()).thenReturn(true);
        when(comment.getGuestPasswordHash()).thenReturn("HASH");

        when(guestPasswordEncoder.matches("raw", "HASH")).thenReturn(true);

        Post post = mock(Post.class);
        when(comment.getPost()).thenReturn(post);

        // when
        commentService.deleteCommentAsGuest(commentId, "raw");

        // then
        verify(post).decreaseCommentCount();
        verify(commentRepository).delete(comment);
    }

    @Test
    @DisplayName("댓글 삭제(게스트): 비밀번호가 일치하지 않으면 ACCESS_DENIED 예외를 던진다")
    void deleteCommentAsGuest_fail_passwordMismatch() {

        // given
        Long commentId = 2L;

        Comment comment = mock(Comment.class);
        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));
        when(comment.isGuest()).thenReturn(true);
        when(comment.getGuestPasswordHash()).thenReturn("HASH");

        when(guestPasswordEncoder.matches("wrong", "HASH")).thenReturn(false);

        // when
        CustomAppException ex = assertThrows(
                CustomAppException.class,
                () -> commentService.deleteCommentAsGuest(commentId, "wrong")
        );

        // then
        assertEquals(ErrorType.ACCESS_DENIED, ex.getErrorType());
        verify(commentRepository, never()).delete(any());
    }

    @Test
    @DisplayName("댓글 삭제(게스트): 회원 댓글이면 ACCESS_DENIED 예외를 던진다")
    void deleteCommentAsGuest_fail_memberComment() {

        // given
        Long commentId = 2L;

        Comment comment = mock(Comment.class);
        when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));
        when(comment.isGuest()).thenReturn(false);

        // when
        CustomAppException ex = assertThrows(
                CustomAppException.class,
                () -> commentService.deleteCommentAsGuest(commentId, "pw")
        );

        // then
        assertEquals(ErrorType.ACCESS_DENIED, ex.getErrorType());
        verify(guestPasswordEncoder, never()).matches(anyString(), anyString());
        verify(commentRepository, never()).delete(any());
    }

    @Test
    @DisplayName("댓글 페이지 조회: page 정보와 트리 응답이 포함된다(루트 1개 + 자식 1개)")
    void getCommentPage_success_tree() {

        // given
        Long postId = 10L;
        int page = 0;

        Post post = mock(Post.class);
        when(postService.getPost(postId)).thenReturn(post);
        when(post.getCommentCount()).thenReturn(2);

        CommentTreeRowView r1 = rowView(1L, null, "root", 0, 100L, null, 0, 0);
        CommentTreeRowView r2 = rowView(2L, 1L, "child", 1, 100L, null, 0, 0);

        when(commentRepository.findPagedCommentTree(postId, CommentService.COMMENTS_SIZE, 0))
                .thenReturn(List.of(r1, r2));

        when(userService.getUserIdToNicknameMap(Set.of(100L)))
                .thenReturn(Map.of(100L, "닉"));

        // when
        CommentPageResponse res = commentService.getCommentPage(postId, page, null, null);

        // then
        assertEquals(2, res.totalComments());
        assertEquals(0, res.currentPage());
        assertEquals(1, res.totalPages());
        assertFalse(res.hasNext());
        assertFalse(res.hasPrevious());

        List<CommentResponse> roots = res.comments();
        assertEquals(1, roots.size());
        assertEquals(1L, roots.get(0).id());
        assertEquals(1, roots.get(0).children().size());
        assertEquals(2L, roots.get(0).children().get(0).id());
    }

    @Test
    @DisplayName("댓글 페이지 조회(회원): likeCount는 findLikeCountsByIds로, likedByMe는 findReactionForMember로 반영된다")
    void getCommentPage_member_likedByMeTrue_andLikeCountUpdated() {

        // given
        Long postId = 10L;
        UserPrincipal principal = principal(42L);

        Post post = mock(Post.class);
        when(postService.getPost(postId)).thenReturn(post);
        when(post.getCommentCount()).thenReturn(1);

        // CTE 기준 likeCount=2, dislikeCount=0
        CommentTreeRowView r1 = rowView(1L, null, "hi", 0, 100L, null, 2, 0);
        when(commentRepository.findPagedCommentTree(postId, CommentService.COMMENTS_SIZE, 0))
                .thenReturn(List.of(r1));
        when(userService.getUserIdToNicknameMap(Set.of(100L)))
                .thenReturn(Map.of(100L, "nick"));

        // likeCount 갱신: 5로 업데이트
        CommentRepository.LikeCount lc = likeCount(1L, 5, 1);
        when(commentRepository.findLikeCountsByIds(anyCollection()))
                .thenReturn(List.of(lc));

        // 개인 반응: likedByMe=true
        MyReactionView rv = myReaction(1L, 1, 0);
        when(likeRepository.findReactionForMember(anyCollection(), any(), eq(42L)))
                .thenReturn(List.of(rv));

        // when
        CommentPageResponse res = commentService.getCommentPage(postId, 0, principal, null);

        // then
        CommentResponse comment = res.comments().get(0);
        assertEquals(5, comment.likeCount());      // 2 → 5 갱신
        assertEquals(1, comment.dislikeCount());   // 0 → 1 갱신
        assertTrue(comment.likedByMe());
        assertFalse(comment.dislikedByMe());

        verify(likeRepository).findReactionForMember(anyCollection(), any(), eq(42L));
        verify(likeRepository, never()).findReactionForGuest(any(), any(), any());
    }

    @Test
    @DisplayName("댓글 페이지 조회(회원): likes 테이블에 row가 없으면 CTE 기준 likeCount를 유지하고 likedByMe=false다")
    void getCommentPage_member_noSummary_keepsOriginalCountsAndLikedByMeFalse() {

        // given
        Long postId = 10L;
        UserPrincipal principal = principal(42L);

        Post post = mock(Post.class);
        when(postService.getPost(postId)).thenReturn(post);
        when(post.getCommentCount()).thenReturn(1);

        // 좋아요가 하나도 없는 댓글 (likeCount=3 in CTE)
        CommentTreeRowView r1 = rowView(1L, null, "no reaction", 0, 100L, null, 3, 0);
        when(commentRepository.findPagedCommentTree(postId, CommentService.COMMENTS_SIZE, 0))
                .thenReturn(List.of(r1));
        when(userService.getUserIdToNicknameMap(Set.of(100L)))
                .thenReturn(Map.of(100L, "nick"));

        // findLikeCountsByIds, findReactionForMember 모두 빈 리스트 반환 (Mockito 기본값)

        // when
        CommentPageResponse res = commentService.getCommentPage(postId, 0, principal, null);

        // then
        CommentResponse comment = res.comments().get(0);
        assertEquals(3, comment.likeCount());   // CTE 값 유지
        assertEquals(0, comment.dislikeCount());
        assertFalse(comment.likedByMe());
        assertFalse(comment.dislikedByMe());
    }

    @Test
    @DisplayName("댓글 페이지 조회(게스트): findReactionForGuest를 호출하고 likedByMe=true를 반영한다")
    void getCommentPage_guest_likedByMeTrue() {

        // given
        Long postId = 10L;
        String guestId = "guest-abc";

        Post post = mock(Post.class);
        when(postService.getPost(postId)).thenReturn(post);
        when(post.getCommentCount()).thenReturn(1);

        CommentTreeRowView r1 = rowView(1L, null, "hello", 0, 100L, null, 0, 0);
        when(commentRepository.findPagedCommentTree(postId, CommentService.COMMENTS_SIZE, 0))
                .thenReturn(List.of(r1));
        when(userService.getUserIdToNicknameMap(Set.of(100L)))
                .thenReturn(Map.of(100L, "nick"));

        CommentRepository.LikeCount lc = likeCount(1L, 2, 0);
        when(commentRepository.findLikeCountsByIds(anyCollection()))
                .thenReturn(List.of(lc));

        MyReactionView rv = myReaction(1L, 1, 0);
        when(likeRepository.findReactionForGuest(anyCollection(), any(), eq(guestId)))
                .thenReturn(List.of(rv));

        // when
        CommentPageResponse res = commentService.getCommentPage(postId, 0, null, guestId);

        // then
        CommentResponse comment = res.comments().get(0);
        assertEquals(2, comment.likeCount());
        assertTrue(comment.likedByMe());
        assertFalse(comment.dislikedByMe());

        verify(likeRepository).findReactionForGuest(anyCollection(), any(), eq(guestId));
        verify(likeRepository, never()).findReactionForMember(any(), any(), any());
    }

    @Test
    @DisplayName("댓글 페이지 조회: principal과 guestIdentifier가 모두 null이면 반응 쿼리를 호출하지 않는다")
    void getCommentPage_noPrincipalNoGuest_skipsReactionQuery() {

        // given
        Long postId = 10L;

        Post post = mock(Post.class);
        when(postService.getPost(postId)).thenReturn(post);
        when(post.getCommentCount()).thenReturn(1);

        CommentTreeRowView r1 = rowView(1L, null, "content", 0, 100L, null, 3, 1);
        when(commentRepository.findPagedCommentTree(postId, CommentService.COMMENTS_SIZE, 0))
                .thenReturn(List.of(r1));
        when(userService.getUserIdToNicknameMap(Set.of(100L)))
                .thenReturn(Map.of(100L, "nick"));

        // when
        CommentPageResponse res = commentService.getCommentPage(postId, 0, null, null);

        // then
        verify(likeRepository, never()).findReactionForMember(any(), any(), any());
        verify(likeRepository, never()).findReactionForGuest(any(), any(), any());

        // likeCount는 CTE 값 그대로 (mergeLikeCounts는 호출됨)
        CommentResponse comment = res.comments().get(0);
        assertEquals(3, comment.likeCount());
        assertEquals(1, comment.dislikeCount());
        assertFalse(comment.likedByMe());
    }

    @Test
    @DisplayName("댓글 페이지 조회(회원): 대댓글(children)에도 likeCount 갱신과 개인 반응이 적용된다")
    void getCommentPage_member_reactionAppliedToChildren() {

        // given
        Long postId = 10L;
        UserPrincipal principal = principal(42L);

        Post post = mock(Post.class);
        when(postService.getPost(postId)).thenReturn(post);
        when(post.getCommentCount()).thenReturn(2);

        // root(id=1) + child(id=2)
        CommentTreeRowView r1 = rowView(1L, null, "root",  0, 100L, null, 0, 0);
        CommentTreeRowView r2 = rowView(2L, 1L,   "child", 1, 100L, null, 0, 0);
        when(commentRepository.findPagedCommentTree(postId, CommentService.COMMENTS_SIZE, 0))
                .thenReturn(List.of(r1, r2));
        when(userService.getUserIdToNicknameMap(Set.of(100L)))
                .thenReturn(Map.of(100L, "nick"));

        // child(id=2): dislikeCount=3 갱신
        CommentRepository.LikeCount childLc = likeCount(2L, 0, 3);
        when(commentRepository.findLikeCountsByIds(anyCollection()))
                .thenReturn(List.of(childLc));

        // child(id=2): dislikedByMe=true
        MyReactionView childRv = myReaction(2L, 0, 1);
        when(likeRepository.findReactionForMember(anyCollection(), any(), eq(42L)))
                .thenReturn(List.of(childRv));

        // when
        CommentPageResponse res = commentService.getCommentPage(postId, 0, principal, null);

        // then
        CommentResponse root  = res.comments().get(0);
        CommentResponse child = root.children().get(0);

        assertFalse(root.likedByMe());
        assertFalse(root.dislikedByMe());

        assertEquals(3, child.dislikeCount());   // 0 → 3 갱신
        assertFalse(child.likedByMe());
        assertTrue(child.dislikedByMe());        // dislikedByMe=true 반영
    }

    @Test
    @DisplayName("댓글 조회: 없으면 COMMENT_NOT_FOUND 예외를 던진다")
    void getComment_fail_notFound() {

        // given
        when(commentRepository.findById(999L)).thenReturn(Optional.empty());

        // when
        CustomAppException ex = assertThrows(
                CustomAppException.class,
                () -> commentService.getComment(999L)
        );

        // then
        assertEquals(ErrorType.COMMENT_NOT_FOUND, ex.getErrorType());
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

    private MyReactionView myReaction(Long targetId, int likedByMe, int dislikedByMe) {
        MyReactionView rv = mock(MyReactionView.class);
        when(rv.getTargetId()).thenReturn(targetId);
        when(rv.getLikedByMe()).thenReturn(likedByMe);
        when(rv.getDislikedByMe()).thenReturn(dislikedByMe);
        return rv;
    }

    private CommentRepository.LikeCount likeCount(Long id, int likeCount, int dislikeCount) {
        CommentRepository.LikeCount lc = mock(CommentRepository.LikeCount.class);
        when(lc.getId()).thenReturn(id);
        when(lc.getLikeCount()).thenReturn(likeCount);
        when(lc.getDislikeCount()).thenReturn(dislikeCount);
        return lc;
    }

    private CommentTreeRowView rowView(Long id, Long parentId, String content, int depth,
                                       Long userId, String guestNickname,
                                       int likeCount, int dislikeCount) {
        CommentTreeRowView v = mock(CommentTreeRowView.class);
        when(v.getId()).thenReturn(id);
        when(v.getParentId()).thenReturn(parentId);
        when(v.getContent()).thenReturn(content);
        when(v.getDepth()).thenReturn(depth);
        when(v.getUserId()).thenReturn(userId);
        when(v.getGuestNickname()).thenReturn(guestNickname);
        when(v.getLikeCount()).thenReturn(likeCount);
        when(v.getDislikeCount()).thenReturn(dislikeCount);
        return v;
    }
}
