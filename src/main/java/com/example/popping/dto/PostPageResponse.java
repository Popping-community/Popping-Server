package com.example.popping.dto;

import java.util.List;

public record PostPageResponse(

        List<PostListItemResponse> posts,
        int totalPosts,
        int currentPage,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious

) {
    public PostPageResponse {
        posts = posts == null ? List.of() : List.copyOf(posts);
    }
}