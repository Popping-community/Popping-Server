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
 *
 * <p>{@code reactionVersion} rises with every change to the target's counters. Broadcasts can
 * arrive late or out of order, especially once they are relayed between instances, so a client
 * keeps the highest version it has shown and ignores anything lower.
 */
public record LikeResponse(
        Long targetId,
        Like.TargetType targetType,
        LikeAction action,
        int likeCount,
        int dislikeCount,
        long reactionVersion
) {
    public enum LikeAction {
        LIKED,
        UNLIKED,
        DISLIKED,
        UNDISLIKED
    }
}
