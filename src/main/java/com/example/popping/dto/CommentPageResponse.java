package com.example.popping.dto;

import java.util.List;

public record CommentPageResponse(

        List<CommentResponse> comments,
        int totalComments,
        int currentPage,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious

) {

    public CommentPageResponse {
        comments = comments == null ? List.of() : List.copyOf(comments);
    }
}
