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

        validateActor(user, guestIdentifier);

        Like.TargetType targetType = req.targetType();
        Like.Type type = req.type();
        Long targetId = req.targetId();

        Like existing = findExisting(targetType, targetId, type, user, guestIdentifier);
        boolean removed = existing != null;

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

        return new LikeResponse(targetId, targetType, resolveAction(type, removed));
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

    private LikeResponse.LikeAction resolveAction(Like.Type type, boolean removed) {
        if (type == Like.Type.LIKE) {
            return removed
                    ? LikeResponse.LikeAction.UNLIKED
                    : LikeResponse.LikeAction.LIKED;
        }

        return removed
                ? LikeResponse.LikeAction.UNDISLIKED
                : LikeResponse.LikeAction.DISLIKED;
    }
}
