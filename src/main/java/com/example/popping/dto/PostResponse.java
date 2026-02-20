package com.example.popping.dto;

import com.example.popping.domain.Post;

public record PostResponse(

        Long id,
        String title,
        String content,
        String authorName,
        String boardName,
        Long authorId,
        String guestNickname,
        Long viewCount,
        int commentCount,
        int likeCount,
        int dislikeCount

) {
    public static PostResponse from(Post post) {

        boolean isGuest = post.isGuest();

        return new PostResponse(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                isGuest ? post.getGuestNickname() : post.getAuthor().getNickname(),
                post.getBoard().getName(),
                isGuest ? null : post.getAuthor().getId(),
                isGuest ? post.getGuestNickname() : null,
                post.getViewCount(),
                post.getCommentCount(),
                post.getLikeCount(),
                post.getDislikeCount()
        );
    }
}

