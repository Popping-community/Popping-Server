package com.example.popping.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

import com.example.popping.domain.Like;

/**
 * Who is reacting is never part of the request: the member comes from authentication and a
 * guest from the signed cookie the server already verified. Pages served before this change
 * still send {@code guestIdentifier}; it is ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LikeRequest(
        @NotNull Long targetId,
        @NotNull Like.TargetType targetType,
        @NotNull Like.Type type
) {}
