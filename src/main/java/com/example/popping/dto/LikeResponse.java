package com.example.popping.dto;

import com.example.popping.domain.Like;

public record LikeResponse(
        Long targetId,
        Like.TargetType targetType,
        int likeCount,
        int dislikeCount
) {}
