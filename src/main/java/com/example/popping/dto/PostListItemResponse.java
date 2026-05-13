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
        int dislikeCount

) {
}
