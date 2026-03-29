package com.example.popping.dto;

import java.util.List;

public record PostPageResponse(

        List<PostListItemResponse> posts,
        int currentPage,
        boolean hasNext,
        boolean hasPrevious

) {
    public PostPageResponse {
        posts = posts == null ? List.of() : List.copyOf(posts);
    }
}