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

    public static PostResponse from(Post post) {
        return PostResponse.builder()
                .id(post.getId())
                .title(post.getTitle())
                .content(post.getContent())
                .authorName(post.getAuthor().getNickname())
                .boardName(post.getBoard().getName())
                .authorId(post.getAuthor().getId())
                .build();
    }
}
