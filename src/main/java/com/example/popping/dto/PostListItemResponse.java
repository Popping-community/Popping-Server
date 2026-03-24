package com.example.popping.dto;

public record PostListItemResponse(

        Long id,
        String title,
        String authorName,
        Long authorId,
        String guestNickname,
        Long viewCount,
        int commentCount,
        int likeCount,
        int dislikeCount,
        boolean likedByMe,
        boolean dislikedByMe

) {
    public PostListItemResponse withReactions(boolean likedByMe, boolean dislikedByMe) {
        return new PostListItemResponse(
                id, title, authorName, authorId, guestNickname,
                viewCount, commentCount, likeCount, dislikeCount,
                likedByMe, dislikedByMe
        );
    }
}
