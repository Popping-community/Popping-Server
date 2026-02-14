package com.example.popping.service;

import java.util.Optional;

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

    public LikeResponse toggleLike(LikeRequest likeRequest, UserPrincipal userPrincipal) {

        User user = userPrincipal != null ? userService.getLoginUserById(userPrincipal.getUserId()) : null;
        Optional<Like> existing = likeRepository.findByTargetTypeAndTargetIdAndUserAndGuestIdentifierAndType(
                likeRequest.getTargetType(), likeRequest.getTargetId(), user, likeRequest.getGuestIdentifier(), likeRequest.getType());

        if (existing.isPresent()) {
            likeRepository.delete(existing.get());
            updateLikeCount(likeRequest.getTargetType(), likeRequest.getType(), likeRequest.getTargetId(), -1);
        } else {
            likeRepository.save(likeRequest.toEntity(user));
            updateLikeCount(likeRequest.getTargetType(), likeRequest.getType(), likeRequest.getTargetId(), 1);
        }

        if (likeRequest.getTargetType() == Like.TargetType.POST) {
            return LikeResponse.from(
                    likeRequest.getTargetId(),
                    likeRequest.getTargetType(),
                    postService.getPost(likeRequest.getTargetId()).getLikeCount(),
                    postService.getPost(likeRequest.getTargetId()).getDislikeCount());
        } else if (likeRequest.getTargetType() == Like.TargetType.COMMENT) {
            return LikeResponse.from(
                    likeRequest.getTargetId(),
                    likeRequest.getTargetType(),
                    commentService.getComment(likeRequest.getTargetId()).getLikeCount(),
                    commentService.getComment(likeRequest.getTargetId()).getDislikeCount());
        } else {
            throw new CustomAppException(ErrorType.INVALID_TARGET_TYPE);
        }
    }

    private void updateLikeCount(Like.TargetType targetType, Like.Type type, Long targetId, int delta) {
        if (targetType == Like.TargetType.POST) {
            if (type == Like.Type.LIKE) {
                postService.updateLikeCount(targetId, delta);
            } else {
                postService.updateDislikeCount(targetId, delta);
            }
        } else if (targetType == Like.TargetType.COMMENT) {
            if (type == Like.Type.LIKE) {
                commentService.updateLikeCount(targetId, delta);
            } else {
                commentService.updateDislikeCount(targetId, delta);
            }
        }
    }
}
