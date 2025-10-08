package com.example.popping.dto;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CommentPageResponse {
    private List<CommentResponse> comments;
    private int totalComments;
    private int currentPage;
    private int totalPages;
    private boolean hasNext;
    private boolean hasPrevious;
}
