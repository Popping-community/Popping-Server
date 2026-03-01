package com.example.popping.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.*;
import com.example.popping.dto.LikeRequest;
import com.example.popping.dto.LikeResponse;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.LikeRepository;

@Service
@Transactional
@RequiredArgsConstructor
public class LikeService {

    private final LikeRepository likeRepository;
    private final PostService postService;
    private final CommentService commentService;
    private final UserService userService;

    public LikeResponse toggleLike(LikeRequest req, UserPrincipal principal) {

        User user = getUser(principal);
        String guestIdentifier = req.guestIdentifier();

        // 회원/게스트 중 하나는 반드시 확정되어야 함
        validateActor(user, guestIdentifier);

        Like.TargetType targetType = req.targetType();
        Like.Type type = req.type();
        Long targetId = req.targetId();

        Like existing = findExisting(targetType, targetId, type, user, guestIdentifier);

        if (existing != null) {
            likeRepository.delete(existing);
            applyDelta(targetType, type, targetId, -1);
        } else {
            Like like = (user != null)
                    ? Like.createByMember(type, targetType, targetId, user)
                    : Like.createByGuest(type, targetType, targetId, guestIdentifier);

            likeRepository.save(like);
            applyDelta(targetType, type, targetId, 1);
        }

        LikeCounts counts = loadCounts(targetType, targetId);
        return new LikeResponse(targetId, targetType, counts.likeCount(), counts.dislikeCount());
    }

    private User getUser(UserPrincipal principal) {
        if (principal == null) return null;
        return userService.getLoginUserById(principal.getUserId());
    }

    private void validateActor(User user, String guestIdentifier) {
        if (user == null && (guestIdentifier == null || guestIdentifier.isBlank())) {
            throw new CustomAppException(ErrorType.ACCESS_DENIED);
        }
    }

    private Like findExisting(Like.TargetType targetType, Long targetId, Like.Type type,
                              User user, String guestIdentifier) {
        return likeRepository
                .findByTargetTypeAndTargetIdAndUserAndGuestIdentifierAndType(
                        targetType, targetId, user, guestIdentifier, type
                )
                .orElse(null);
    }

    private void applyDelta(Like.TargetType targetType, Like.Type type, Long targetId, int delta) {
        switch (targetType) {
            case POST -> applyDeltaToPost(type, targetId, delta);
            case COMMENT -> applyDeltaToComment(type, targetId, delta);
            default -> throw new CustomAppException(ErrorType.INVALID_TARGET_TYPE);
        }
    }

    private void applyDeltaToPost(Like.Type type, Long postId, int delta) {
        if (type == Like.Type.LIKE) postService.updateLikeCount(postId, delta);
        else postService.updateDislikeCount(postId, delta);
    }

    private void applyDeltaToComment(Like.Type type, Long commentId, int delta) {
        if (type == Like.Type.LIKE) commentService.updateLikeCount(commentId, delta);
        else commentService.updateDislikeCount(commentId, delta);
    }

    private LikeCounts loadCounts(Like.TargetType targetType, Long targetId) {
        return switch (targetType) {
            case POST -> {
                Post post = postService.getPost(targetId);
                yield new LikeCounts(post.getLikeCount(), post.getDislikeCount());
            }
            case COMMENT -> {
                Comment comment = commentService.getComment(targetId);
                yield new LikeCounts(comment.getLikeCount(), comment.getDislikeCount());
            }
        };
    }

    private record LikeCounts(int likeCount, int dislikeCount) {}
}
