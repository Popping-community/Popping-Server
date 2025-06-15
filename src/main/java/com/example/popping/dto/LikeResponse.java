package com.example.popping.dto;

import lombok.Builder;
import lombok.Getter;

import com.example.popping.domain.Like;

@Getter
@Builder
public class LikeResponse {
    private Long targetId;
    private Like.TargetType targetType;
    private int likeCount;
    private int dislikeCount;

    public static LikeResponse from(Long targetId, Like.TargetType targetType, int likeCount, int dislikeCount) {
        return LikeResponse.builder()
                .targetId(targetId)
                .targetType(targetType)
                .likeCount(likeCount)
                .dislikeCount(dislikeCount)
                .build();
    }
}
