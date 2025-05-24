package com.example.popping.dto;

import lombok.Builder;
import lombok.Getter;

import com.example.popping.domain.Post;

@Getter
@Builder
public class PostResponse {

    private Long id;
    private String title;
    private String content;
    private String authorName;
    private String boardName;
    private Long authorId;
    private String guestNickname;

    public static PostResponse from(Post post) {
        return PostResponse.builder()
                .id(post.getId())
                .title(post.getTitle())
                .content(post.getContent())
                .authorName(post.isGuest() ? post.getGuestNickname() : post.getAuthor().getNickname())
                .boardName(post.getBoard().getName())
                .authorId(post.isGuest() ? null : post.getAuthor().getId())
                .guestNickname(post.isGuest() ? post.getGuestNickname() : null)
                .build();
    }
}
