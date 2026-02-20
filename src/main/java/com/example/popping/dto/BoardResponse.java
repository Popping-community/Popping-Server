package com.example.popping.dto;

import com.example.popping.domain.Board;

public record BoardResponse(

        String name,
        String description,
        String slug,
        String createdBy,
        Long createdById

) {
    public static BoardResponse from(Board board) {
        if (board == null) return null;

        var user = board.getCreatedBy();
        return new BoardResponse(
                board.getName(),
                board.getDescription(),
                board.getSlug(),
                user != null ? user.getNickname() : null,
                user != null ? user.getId() : null
        );
    }
}
