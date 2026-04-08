package com.example.popping.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

import com.example.popping.domain.Like;
import com.example.popping.domain.User;
import com.example.popping.domain.UserPrincipal;
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
    private final GuestIdentifierService guestIdentifierService;

    public LikeResponse addLike(LikeRequest req, UserPrincipal principal) {
        User user = getUser(principal);
        String guestIdentifier = resolveGuestIdentifier(req.guestIdentifier());
        validateActor(user, guestIdentifier);

        int inserted = likeRepository.insertIgnore(
                req.targetType().name(),
                req.targetId(),
                req.type().name(),
                user != null ? user.getId() : null,
                guestIdentifier
        );

        if (inserted > 0) {
            applyDelta(req.targetType(), req.type(), req.targetId(), 1);
        }

        return new LikeResponse(req.targetId(), req.targetType(), addedAction(req.type()));
    }

    public LikeResponse removeLike(LikeRequest req, UserPrincipal principal) {
        User user = getUser(principal);
        String guestIdentifier = resolveGuestIdentifier(req.guestIdentifier());
        validateActor(user, guestIdentifier);

        int deleted = likeRepository.deleteByActor(
                req.targetType(),
                req.targetId(),
                req.type(),
                user,
                guestIdentifier
        );

        if (deleted > 0) {
            applyDelta(req.targetType(), req.type(), req.targetId(), -1);
        }

        return new LikeResponse(req.targetId(), req.targetType(), removedAction(req.type()));
    }

    private User getUser(UserPrincipal principal) {
        if (principal == null) return null;
        return userService.getLoginUserById(principal.getUserId());
    }

    /**
     * guestIdentifier가 "uuid.signature" 형태면 서명 검증 후 UUID만 반환.
     * 서명이 없는 구형 값이거나 null이면 그대로 반환.
     */
    private String resolveGuestIdentifier(String raw) {
        if (raw == null) return null;
        return guestIdentifierService.extractUuid(raw).orElse(raw);
    }

    private void validateActor(User user, String guestIdentifier) {
        if (user == null && (guestIdentifier == null || guestIdentifier.isBlank())) {
            throw new CustomAppException(ErrorType.ACCESS_DENIED);
        }
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

    private LikeResponse.LikeAction addedAction(Like.Type type) {
        return type == Like.Type.LIKE
                ? LikeResponse.LikeAction.LIKED
                : LikeResponse.LikeAction.DISLIKED;
    }

    private LikeResponse.LikeAction removedAction(Like.Type type) {
        return type == Like.Type.LIKE
                ? LikeResponse.LikeAction.UNLIKED
                : LikeResponse.LikeAction.UNDISLIKED;
    }
}
