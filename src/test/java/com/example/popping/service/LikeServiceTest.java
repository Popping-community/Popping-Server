package com.example.popping.service;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.popping.domain.Comment;
import com.example.popping.domain.Like;
import com.example.popping.domain.Post;
import com.example.popping.domain.User;
import com.example.popping.domain.UserPrincipal;
import com.example.popping.dto.LikeRequest;
import com.example.popping.dto.LikeResponse;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.LikeRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LikeServiceTest {

    @Mock LikeRepository likeRepository;
    @Mock PostService postService;
    @Mock CommentService commentService;
    @Mock UserService userService;

    @InjectMocks LikeService likeService;

    @Test
    @DisplayName("좋아요 토글(회원/POST/LIKE): 기존 없으면 저장하고 likeCount를 +1 한다")
    void toggleLike_member_post_like_create() {

        // given
        LikeRequest req = new LikeRequest(10L, Like.TargetType.POST, Like.Type.LIKE, null);
        UserPrincipal principal = principal(1L);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);

        when(likeRepository.findByTargetTypeAndTargetIdAndUserAndGuestIdentifierAndType(
                Like.TargetType.POST, 10L, user, null, Like.Type.LIKE
        )).thenReturn(Optional.empty());

        Post post = postWithCounts(3, 1);
        when(postService.getPost(10L)).thenReturn(post);

        // when
        LikeResponse res = likeService.toggleLike(req, principal);

        // then
        ArgumentCaptor<Like> captor = ArgumentCaptor.forClass(Like.class);
        verify(likeRepository).save(captor.capture());

        Like saved = captor.getValue();
        assertEquals(10L, saved.getTargetId());
        assertEquals(Like.TargetType.POST, saved.getTargetType());
        assertEquals(Like.Type.LIKE, saved.getType());
        assertSame(user, saved.getUser());
        assertNull(saved.getGuestIdentifier());

        verify(postService).updateLikeCount(10L, 1);
        verify(postService, never()).updateDislikeCount(anyLong(), anyInt());
        verify(commentService, never()).updateLikeCount(anyLong(), anyInt());
        verify(commentService, never()).updateDislikeCount(anyLong(), anyInt());

        assertEquals(10L, res.targetId());
        assertEquals(Like.TargetType.POST, res.targetType());
        assertEquals(3, res.likeCount());
        assertEquals(1, res.dislikeCount());
    }

    @Test
    @DisplayName("좋아요 토글(회원/COMMENT/DISLIKE): 기존 있으면 삭제하고 dislikeCount를 -1 한다")
    void toggleLike_member_comment_dislike_delete() {

        // given
        LikeRequest req = new LikeRequest(20L, Like.TargetType.COMMENT, Like.Type.DISLIKE, null);
        UserPrincipal principal = principal(1L);

        User user = userAuthOnly();
        when(userService.getLoginUserById(1L)).thenReturn(user);

        Like existing = mock(Like.class);
        when(likeRepository.findByTargetTypeAndTargetIdAndUserAndGuestIdentifierAndType(
                Like.TargetType.COMMENT, 20L, user, null, Like.Type.DISLIKE
        )).thenReturn(Optional.of(existing));

        Comment comment = commentWithCounts(7, 4);
        when(commentService.getComment(20L)).thenReturn(comment);

        // when
        LikeResponse res = likeService.toggleLike(req, principal);

        // then
        verify(likeRepository).delete(existing);

        verify(commentService).updateDislikeCount(20L, -1);
        verify(commentService, never()).updateLikeCount(anyLong(), anyInt());
        verify(postService, never()).updateLikeCount(anyLong(), anyInt());
        verify(postService, never()).updateDislikeCount(anyLong(), anyInt());

        assertEquals(20L, res.targetId());
        assertEquals(Like.TargetType.COMMENT, res.targetType());
        assertEquals(7, res.likeCount());
        assertEquals(4, res.dislikeCount());
    }

    @Test
    @DisplayName("좋아요 토글(게스트/POST/DISLIKE): 기존 없으면 저장하고 dislikeCount를 +1 한다")
    void toggleLike_guest_post_dislike_create() {

        // given
        LikeRequest req = new LikeRequest(30L, Like.TargetType.POST, Like.Type.DISLIKE, "guest-abc");
        UserPrincipal principal = null;

        when(likeRepository.findByTargetTypeAndTargetIdAndUserAndGuestIdentifierAndType(
                Like.TargetType.POST, 30L, null, "guest-abc", Like.Type.DISLIKE
        )).thenReturn(Optional.empty());

        Post post = postWithCounts(0, 9);
        when(postService.getPost(30L)).thenReturn(post);

        // when
        LikeResponse res = likeService.toggleLike(req, principal);

        // then
        ArgumentCaptor<Like> captor = ArgumentCaptor.forClass(Like.class);
        verify(likeRepository).save(captor.capture());

        Like saved = captor.getValue();
        assertEquals(30L, saved.getTargetId());
        assertEquals(Like.TargetType.POST, saved.getTargetType());
        assertEquals(Like.Type.DISLIKE, saved.getType());
        assertNull(saved.getUser());
        assertEquals("guest-abc", saved.getGuestIdentifier());

        verify(postService).updateDislikeCount(30L, 1);
        verify(postService, never()).updateLikeCount(anyLong(), anyInt());

        assertEquals(30L, res.targetId());
        assertEquals(Like.TargetType.POST, res.targetType());
        assertEquals(0, res.likeCount());
        assertEquals(9, res.dislikeCount());
    }

    @Test
    @DisplayName("좋아요 토글: 회원/게스트 모두 없으면 ACCESS_DENIED 예외를 던진다")
    void toggleLike_fail_noActor() {

        // given
        LikeRequest req = new LikeRequest(40L, Like.TargetType.POST, Like.Type.LIKE, "  ");
        UserPrincipal principal = null;

        // when
        CustomAppException ex = assertThrows(
                CustomAppException.class,
                () -> likeService.toggleLike(req, principal)
        );

        // then
        assertEquals(ErrorType.ACCESS_DENIED, ex.getErrorType());
        verifyNoInteractions(likeRepository, postService, commentService, userService);
    }

    private UserPrincipal principal(Long userId) {
        UserPrincipal p = mock(UserPrincipal.class);
        when(p.getUserId()).thenReturn(userId);
        return p;
    }

    private User userAuthOnly() {
        return mock(User.class);
    }

    private Post postWithCounts(int likeCount, int dislikeCount) {
        Post post = mock(Post.class);
        when(post.getLikeCount()).thenReturn(likeCount);
        when(post.getDislikeCount()).thenReturn(dislikeCount);
        return post;
    }

    private Comment commentWithCounts(int likeCount, int dislikeCount) {
        Comment comment = mock(Comment.class);
        when(comment.getLikeCount()).thenReturn(likeCount);
        when(comment.getDislikeCount()).thenReturn(dislikeCount);
        return comment;
    }
}
