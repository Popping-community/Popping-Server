package com.example.popping.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.popping.domain.Like;
import com.example.popping.domain.User;
import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.LikeRequest;
import com.example.popping.dto.LikeResponse;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.LikeCountView;
import com.example.popping.repository.LikeRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LikeServiceTest {

    @Mock LikeRepository likeRepository;
    @Mock PostService postService;
    @Mock CommentService commentService;
    @Mock UserService userService;

    @InjectMocks LikeService likeService;

    @Test
    @DisplayName("좋아요 추가(회원/POST/LIKE): 중복이 아니면 카운트를 +1 한다")
    void addLike_member_post_like_create() {

        // given
        LikeRequest req = new LikeRequest(10L, Like.TargetType.POST, Like.Type.LIKE);
        UserPrincipal principal = principal(1L);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);
        when(user.getId()).thenReturn(1L);

        when(likeRepository.upsertLike("POST", 10L, "LIKE", 1L, null))
                .thenReturn(1);
        when(postService.getLikeCounts(10L)).thenReturn(counts(6, 2, 7));

        // when
        LikeResponse res = likeService.addLike(req, principal, null);

        // then
        verify(postService).updateLikeCount(10L, 1);
        verify(postService, never()).updateDislikeCount(anyLong(), anyInt());

        assertEquals(LikeResponse.LikeAction.LIKED, res.action());
        assertEquals(6, res.likeCount());
        assertEquals(2, res.dislikeCount());
        assertEquals(7, res.reactionVersion());
    }

    @Test
    @DisplayName("좋아요 추가: 이미 있으면 멱등으로 카운트 변경이 없다")
    void addLike_idempotent_whenAlreadyExists() {

        // given
        LikeRequest req = new LikeRequest(10L, Like.TargetType.POST, Like.Type.LIKE);

        when(likeRepository.upsertLike("POST", 10L, "LIKE", null, "guest-1"))
                .thenReturn(0);
        when(postService.getLikeCounts(10L)).thenReturn(counts(5, 2));

        // when
        LikeResponse res = likeService.addLike(req, null, "guest-1");

        // then
        verify(postService, never()).updateLikeCount(anyLong(), anyInt());
        assertEquals(LikeResponse.LikeAction.LIKED, res.action());
    }

    @Test
    @DisplayName("중복 좋아요: 응답이 DB의 현재 카운트를 그대로 실어 화면 드리프트를 막는다")
    void addLike_duplicate_reportsUnchangedAbsoluteCounts() {

        // given — DB already holds this like, so the row insert is a no-op
        LikeRequest req = new LikeRequest(10L, Like.TargetType.POST, Like.Type.LIKE);

        when(likeRepository.upsertLike("POST", 10L, "LIKE", null, "guest-1"))
                .thenReturn(0);
        when(postService.getLikeCounts(10L)).thenReturn(counts(5, 2, 8));

        // when
        LikeResponse res = likeService.addLike(req, null, "guest-1");

        // then — the payload must be the unchanged DB state, not an implied increment.
        // Clients assign these values, so a duplicate request leaves the display untouched.
        assertEquals(5, res.likeCount());
        assertEquals(2, res.dislikeCount());
        // The version is the stored one too: a no-op must not look newer than what clients hold.
        assertEquals(8, res.reactionVersion());
        verify(postService, never()).updateLikeCount(anyLong(), anyInt());
    }

    @Test
    @DisplayName("중복 취소: 응답이 DB의 현재 카운트를 그대로 실어 화면 드리프트를 막는다")
    void removeLike_duplicate_reportsUnchangedAbsoluteCounts() {

        // given — nothing to delete, so the counters must not move
        LikeRequest req = new LikeRequest(20L, Like.TargetType.COMMENT, Like.Type.LIKE);

        when(likeRepository.deleteByGuest(Like.TargetType.COMMENT, 20L, Like.Type.LIKE, "guest-1"))
                .thenReturn(0);
        when(commentService.getLikeCounts(20L)).thenReturn(counts(3, 1));

        // when
        LikeResponse res = likeService.removeLike(req, null, "guest-1");

        // then
        assertEquals(3, res.likeCount());
        assertEquals(1, res.dislikeCount());
        verify(commentService, never()).updateLikeCount(anyLong(), anyInt());
    }

    @Test
    @DisplayName("싫어요 제거(회원/COMMENT/DISLIKE): 있으면 카운트를 -1 한다")
    void removeLike_member_comment_dislike_delete() {

        // given
        LikeRequest req = new LikeRequest(20L, Like.TargetType.COMMENT, Like.Type.DISLIKE);
        UserPrincipal principal = principal(1L);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);

        when(likeRepository.deleteByUser(Like.TargetType.COMMENT, 20L, Like.Type.DISLIKE, user))
                .thenReturn(1);
        when(commentService.getLikeCounts(20L)).thenReturn(counts(3, 0));

        // when
        LikeResponse res = likeService.removeLike(req, principal, null);

        // then
        verify(commentService).updateDislikeCount(20L, -1);
        verify(commentService, never()).updateLikeCount(anyLong(), anyInt());

        assertEquals(LikeResponse.LikeAction.UNDISLIKED, res.action());
        assertEquals(3, res.likeCount());
        assertEquals(0, res.dislikeCount());
    }

    @Test
    @DisplayName("좋아요 제거: 없으면 멱등으로 카운트 변경이 없다")
    void removeLike_idempotent_whenNotExists() {

        // given
        LikeRequest req = new LikeRequest(20L, Like.TargetType.COMMENT, Like.Type.LIKE);

        when(likeRepository.deleteByGuest(Like.TargetType.COMMENT, 20L, Like.Type.LIKE, "guest-1"))
                .thenReturn(0);
        when(commentService.getLikeCounts(20L)).thenReturn(counts(3, 1));

        // when
        LikeResponse res = likeService.removeLike(req, null, "guest-1");

        // then
        verify(commentService, never()).updateLikeCount(anyLong(), anyInt());
        assertEquals(LikeResponse.LikeAction.UNLIKED, res.action());
    }

    @Test
    @DisplayName("회원이면 게스트 쿠키가 함께 와도 회원으로만 기록한다")
    void addLike_member_ignoresGuestIdentity() {

        // given
        LikeRequest req = new LikeRequest(10L, Like.TargetType.POST, Like.Type.LIKE);
        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);
        when(user.getId()).thenReturn(1L);
        when(likeRepository.upsertLike("POST", 10L, "LIKE", 1L, null)).thenReturn(1);
        when(postService.getLikeCounts(10L)).thenReturn(counts(1, 0));

        // when
        likeService.addLike(req, principal(1L), "guest-1");

        // then
        verify(likeRepository).upsertLike("POST", 10L, "LIKE", 1L, null);
    }

    @Test
    @DisplayName("취소는 행위자 한 명의 행만 지운다: 회원은 회원 행, 게스트는 게스트 행")
    void removeLike_deletesOnlyTheActingActorsRow() {

        // given
        LikeRequest req = new LikeRequest(20L, Like.TargetType.COMMENT, Like.Type.LIKE);
        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);
        when(commentService.getLikeCounts(20L)).thenReturn(counts(0, 0));

        // when
        likeService.removeLike(req, principal(1L), "guest-1");
        likeService.removeLike(req, null, "guest-2");

        // then
        verify(likeRepository).deleteByUser(Like.TargetType.COMMENT, 20L, Like.Type.LIKE, user);
        verify(likeRepository).deleteByGuest(Like.TargetType.COMMENT, 20L, Like.Type.LIKE, "guest-2");
        verify(likeRepository, never()).deleteByGuest(any(), anyLong(), any(), eq("guest-1"));
    }

    @Test
    @DisplayName("좋아요 처리: 회원/게스트 모두 없으면 ACCESS_DENIED 예외를 던진다")
    void like_fail_noActor() {

        // given
        LikeRequest req = new LikeRequest(40L, Like.TargetType.POST, Like.Type.LIKE);

        // when
        CustomAppException ex1 = assertThrows(
                CustomAppException.class,
                () -> likeService.addLike(req, null, "  ")
        );
        CustomAppException ex2 = assertThrows(
                CustomAppException.class,
                () -> likeService.removeLike(req, null, "  ")
        );

        // then
        assertEquals(ErrorType.ACCESS_DENIED, ex1.getErrorType());
        assertEquals(ErrorType.ACCESS_DENIED, ex2.getErrorType());
    }

    @Test
    @DisplayName("대상이 사라진 경우: 카운트를 읽지 못하면 예외를 던져 롤백한다")
    void addLike_fail_whenTargetMissing() {

        // given
        LikeRequest req = new LikeRequest(99L, Like.TargetType.POST, Like.Type.LIKE);

        when(likeRepository.upsertLike("POST", 99L, "LIKE", null, "guest-1"))
                .thenReturn(1);
        when(postService.getLikeCounts(99L))
                .thenThrow(new CustomAppException(ErrorType.POST_NOT_FOUND));

        // when
        CustomAppException ex = assertThrows(
                CustomAppException.class,
                () -> likeService.addLike(req, null, "guest-1")
        );

        // then
        assertEquals(ErrorType.POST_NOT_FOUND, ex.getErrorType());
    }

    private UserPrincipal principal(Long userId) {
        UserPrincipal p = mock(UserPrincipal.class);
        when(p.getUserId()).thenReturn(userId);
        return p;
    }

    private User userAuthOnly() {
        return mock(User.class);
    }

    private static LikeCountView counts(int likeCount, int dislikeCount) {
        return counts(likeCount, dislikeCount, 0);
    }

    private static LikeCountView counts(int likeCount, int dislikeCount, long reactionVersion) {
        return new LikeCountView() {
            @Override
            public int getLikeCount() {
                return likeCount;
            }

            @Override
            public int getDislikeCount() {
                return dislikeCount;
            }

            @Override
            public long getReactionVersion() {
                return reactionVersion;
            }
        };
    }
}
