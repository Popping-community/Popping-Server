package com.example.popping.dto;

import java.util.List;
import java.util.Map;

import com.example.popping.repository.CommentTreeRowView;

public record CommentResponse(

        Long id,
        String content,
        String authorName,
        Long authorId,
        String guestNickname,
        int likeCount,
        int dislikeCount,
        Long parentId,
        int depth,
        List<CommentResponse> children

) {

    public CommentResponse {
        children = children == null ? List.of() : List.copyOf(children);
    }

    public static CommentResponse mapToResponse(
            CommentTreeRowView row,
            Map<Long, String> userIdToNickname
    ) {
        Long userId = row.getUserId();
        String guestNickname = row.getGuestNickname();

        String authorName = (userId != null)
                ? userIdToNickname.getOrDefault(userId, "알 수 없음")
                : guestNickname;

        return new CommentResponse(
                row.getId(),
                row.getContent(),
                authorName,
                userId,
                userId == null ? guestNickname : null,
                row.getLikeCount() == null ? 0 : row.getLikeCount(),
                row.getDislikeCount() == null ? 0 : row.getDislikeCount(),
                row.getParentId(),
                row.getDepth() == null ? 0 : row.getDepth(),
                List.of() // record에서 방어적 복사하니까 불변 빈 리스트
        );
    }
}