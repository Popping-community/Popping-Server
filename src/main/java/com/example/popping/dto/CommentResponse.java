package com.example.popping.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.Builder;
import lombok.Getter;

import com.example.popping.domain.Comment;

@Getter
@Builder
public class CommentResponse {

    private Long id;
    private String content;
    private String authorName;
    private Long authorId;
    private String guestNickname;
    private int likeCount;
    private int dislikeCount;
    private Long parentId;
    private int depth;
    private List<CommentResponse> children;

    public static CommentResponse from(Comment comment) {
        return CommentResponse.builder()
                .id(comment.getId())
                .content(comment.getContent())
                .authorName(comment.isGuest() ? comment.getGuestNickname() : comment.getAuthor().getNickname())
                .authorId(comment.isGuest() ? null : comment.getAuthor().getId())
                .guestNickname(comment.isGuest() ? comment.getGuestNickname() : null)
                .likeCount(comment.getLikeCount())
                .dislikeCount(comment.getDislikeCount())
                .parentId(comment.isReply() ? comment.getParent().getId() : null)
                .depth(comment.getDepth())
                .children(comment.getChildren().stream()
                        .map(CommentResponse::from)
                        .collect(Collectors.toList()))
                .build();
    }

    public static CommentResponse mapToResponse(Object[] row, Map<Long, String> userIdToNickname) {
        Long id = (Long) row[0];
        Long parentId = row[1] != null ? (Long) row[1] : null;
        String content = (String) row[3];
        int depth = (int) row[4];
        Long userId = row[6] != null ? (Long) row[6] : null;
        String guestNickname = (String) row[7];
        int likeCount = (int) row[8];
        int dislikeCount = (int) row[9];

        return CommentResponse.builder()
                .id(id)
                .content(content)
                .authorId(userId)
                .authorName(userId != null
                        ? userIdToNickname.getOrDefault(userId, "알 수 없음") : null)
                .guestNickname(userId == null ? guestNickname : null)
                .likeCount(likeCount)
                .dislikeCount(dislikeCount)
                .parentId(parentId)
                .depth(depth)
                .children(new ArrayList<>())
                .build();
    }
}