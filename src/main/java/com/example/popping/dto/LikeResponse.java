package com.example.popping.dto;

import com.example.popping.domain.Like;

/**
 * Broadcast payload for a like/dislike change.
 *
 * <p>Carries the <b>absolute</b> counters rather than a delta. STOMP does not guarantee
 * exactly-once delivery, and a request may be a no-op (liking something already liked),
 * so an increment-based payload drifts away from the database permanently. Clients must
 * assign these values, never add to their current ones.
 *
 * <p>{@code action} describes what the requester asked for; it is not a signal that the
 * database actually changed. Use the counters for display state.
 */
public record LikeResponse(
        Long targetId,
        Like.TargetType targetType,
        LikeAction action,
        int likeCount,
        int dislikeCount
) {
    public enum LikeAction {
        LIKED,
        UNLIKED,
        DISLIKED,
        UNDISLIKED
    }
}
