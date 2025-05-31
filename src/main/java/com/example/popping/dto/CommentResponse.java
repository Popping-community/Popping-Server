package com.example.popping.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

import com.example.popping.domain.Comment;

@Getter
@Builder
public class CommentResponse {

    private Long id;
    private String content;
    private String authorName;
    private Long authorId;
    private String guestNickname;
    private Long parentId;
    private List<CommentResponse> children;

    public static CommentResponse from(Comment comment) {
        return CommentResponse.builder()
                .id(comment.getId())
                .content(comment.getContent())
                .authorName(comment.isGuest() ? comment.getGuestNickname() : comment.getAuthor().getNickname())
                .authorId(comment.isGuest() ? null : comment.getAuthor().getId())
                .guestNickname(comment.isGuest() ? comment.getGuestNickname() : null)
                .parentId(comment.isReply() ? comment.getParent().getId() : null)
                .children(comment.getChildren().stream()
                        .map(CommentResponse::from)
                        .collect(Collectors.toList()))
                .build();
    }
}