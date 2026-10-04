package com.example.popping.dto;

import java.util.List;

/**
 * Current like/dislike counts of a post and of the requested comments under it, each with the
 * reaction version a client compares against relayed {@link LikeResponse} messages.
 */
public record ReactionCountsResponse(Counts post, List<CommentCounts> comments) {

    public record Counts(int likeCount, int dislikeCount, long reactionVersion) {
    }

    public record CommentCounts(Long commentId, int likeCount, int dislikeCount, long reactionVersion) {
    }
}
