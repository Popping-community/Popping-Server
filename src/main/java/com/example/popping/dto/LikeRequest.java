package com.example.popping.dto;

import jakarta.validation.constraints.NotNull;

import com.example.popping.domain.Like;

public record LikeRequest(
        @NotNull Long targetId,
        @NotNull Like.TargetType targetType,
        @NotNull Like.Type type,
        String guestIdentifier
) {}
