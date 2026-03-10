package com.example.popping.dto;

import com.example.popping.domain.Like;

public record LikeResponse(
        Long targetId,
        Like.TargetType targetType,
        LikeAction action
) {
    public enum LikeAction {
        LIKED,
        UNLIKED,
        DISLIKED,
        UNDISLIKED
    }
}
