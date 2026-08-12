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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LikeServiceTest {

    @Mock LikeRepository likeRepository;
    @Mock PostService postService;
    @Mock CommentService commentService;
    @Mock UserService userService;
    @Mock GuestIdentifierService guestIdentifierService;

    @InjectMocks LikeService likeService;

    @Test
    @DisplayName("좋아요 추가(회원/POST/LIKE): 중복이 아니면 카운트를 +1 한다")
    void addLike_member_post_like_create() {

        // given
        LikeRequest req = new LikeRequest(10L, Like.TargetType.POST, Like.Type.LIKE, null);
        UserPrincipal principal = principal(1L);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);
        when(user.getId()).thenReturn(1L);

        when(likeRepository.upsertLike("POST", 10L, "LIKE", 1L, null))
                .thenReturn(1);
        when(postService.getLikeCounts(10L)).thenReturn(counts(6, 2));

        // when
        LikeResponse res = likeService.addLike(req, principal);

        // then
        verify(postService).updateLikeCount(10L, 1);
        verify(postService, never()).updateDislikeCount(anyLong(), anyInt());

        assertEquals(LikeResponse.LikeAction.LIKED, res.action());
        assertEquals(6, res.likeCount());
        assertEquals(2, res.dislikeCount());
    }

    @Test
    @DisplayName("좋아요 추가: 이미 있으면 멱등으로 카운트 변경이 없다")
    void addLike_idempotent_whenAlreadyExists() {

        // given
        LikeRequest req = new LikeRequest(10L, Like.TargetType.POST, Like.Type.LIKE, "guest-1");

        when(likeRepository.upsertLike("POST", 10L, "LIKE", null, "guest-1"))
                .thenReturn(0);
        when(postService.getLikeCounts(10L)).thenReturn(counts(5, 2));

        // when
        LikeResponse res = likeService.addLike(req, null);

        // then
        verify(postService, never()).updateLikeCount(anyLong(), anyInt());
        assertEquals(LikeResponse.LikeAction.LIKED, res.action());
    }

    @Test
    @DisplayName("중복 좋아요: 응답이 DB의 현재 카운트를 그대로 실어 화면 드리프트를 막는다")
    void addLike_duplicate_reportsUnchangedAbsoluteCounts() {

        // given — DB already holds this like, so the row insert is a no-op
        LikeRequest req = new LikeRequest(10L, Like.TargetType.POST, Like.Type.LIKE, "guest-1");

        when(likeRepository.upsertLike("POST", 10L, "LIKE", null, "guest-1"))
                .thenReturn(0);
        when(postService.getLikeCounts(10L)).thenReturn(counts(5, 2));

        // when
        LikeResponse res = likeService.addLike(req, null);

        // then — the payload must be the unchanged DB state, not an implied increment.
        // Clients assign these values, so a duplicate request leaves the display untouched.
        assertEquals(5, res.likeCount());
        assertEquals(2, res.dislikeCount());
        verify(postService, never()).updateLikeCount(anyLong(), anyInt());
    }

    @Test
    @DisplayName("중복 취소: 응답이 DB의 현재 카운트를 그대로 실어 화면 드리프트를 막는다")
    void removeLike_duplicate_reportsUnchangedAbsoluteCounts() {

        // given — nothing to delete, so the counters must not move
        LikeRequest req = new LikeRequest(20L, Like.TargetType.COMMENT, Like.Type.LIKE, "guest-1");

        when(likeRepository.deleteByActor(Like.TargetType.COMMENT, 20L, Like.Type.LIKE, null, "guest-1"))
                .thenReturn(0);
        when(commentService.getLikeCounts(20L)).thenReturn(counts(3, 1));

        // when
        LikeResponse res = likeService.removeLike(req, null);

        // then
        assertEquals(3, res.likeCount());
        assertEquals(1, res.dislikeCount());
        verify(commentService, never()).updateLikeCount(anyLong(), anyInt());
    }

    @Test
    @DisplayName("싫어요 제거(회원/COMMENT/DISLIKE): 있으면 카운트를 -1 한다")
    void removeLike_member_comment_dislike_delete() {

        // given
        LikeRequest req = new LikeRequest(20L, Like.TargetType.COMMENT, Like.Type.DISLIKE, null);
        UserPrincipal principal = principal(1L);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);

        when(likeRepository.deleteByActor(Like.TargetType.COMMENT, 20L, Like.Type.DISLIKE, user, null))
                .thenReturn(1);
        when(commentService.getLikeCounts(20L)).thenReturn(counts(3, 0));

        // when
        LikeResponse res = likeService.removeLike(req, principal);

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
        LikeRequest req = new LikeRequest(20L, Like.TargetType.COMMENT, Like.Type.LIKE, "guest-1");

        when(likeRepository.deleteByActor(Like.TargetType.COMMENT, 20L, Like.Type.LIKE, null, "guest-1"))
                .thenReturn(0);
        when(commentService.getLikeCounts(20L)).thenReturn(counts(3, 1));

        // when
        LikeResponse res = likeService.removeLike(req, null);

        // then
        verify(commentService, never()).updateLikeCount(anyLong(), anyInt());
        assertEquals(LikeResponse.LikeAction.UNLIKED, res.action());
    }

    @Test
    @DisplayName("좋아요 처리: 회원/게스트 모두 없으면 ACCESS_DENIED 예외를 던진다")
    void like_fail_noActor() {

        // given
        LikeRequest req = new LikeRequest(40L, Like.TargetType.POST, Like.Type.LIKE, "  ");

        // when
        CustomAppException ex1 = assertThrows(
                CustomAppException.class,
                () -> likeService.addLike(req, null)
        );
        CustomAppException ex2 = assertThrows(
                CustomAppException.class,
                () -> likeService.removeLike(req, null)
        );

        // then
        assertEquals(ErrorType.ACCESS_DENIED, ex1.getErrorType());
        assertEquals(ErrorType.ACCESS_DENIED, ex2.getErrorType());
    }

    @Test
    @DisplayName("대상이 사라진 경우: 카운트를 읽지 못하면 예외를 던져 롤백한다")
    void addLike_fail_whenTargetMissing() {

        // given
        LikeRequest req = new LikeRequest(99L, Like.TargetType.POST, Like.Type.LIKE, "guest-1");

        when(likeRepository.upsertLike("POST", 99L, "LIKE", null, "guest-1"))
                .thenReturn(1);
        when(postService.getLikeCounts(99L))
                .thenThrow(new CustomAppException(ErrorType.POST_NOT_FOUND));

        // when
        CustomAppException ex = assertThrows(
                CustomAppException.class,
                () -> likeService.addLike(req, null)
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
        return new LikeCountView() {
            @Override
            public int getLikeCount() {
                return likeCount;
            }

            @Override
            public int getDislikeCount() {
                return dislikeCount;
            }
        };
    }
}
