package com.example.popping.dto;

import lombok.Builder;
import lombok.Getter;

import com.example.popping.domain.Board;

@Getter
@Builder
public class BoardResponse {

    private String name;
    private String description;
    private String slug;
    private String createdBy;
    private Long createdById;

    public static BoardResponse from(Board board) {
        return BoardResponse.builder()
                .name(board.getName())
                .description(board.getDescription())
                .slug(board.getSlug())
                .createdBy(board.getCreatedBy().getNickname())
                .createdById(board.getCreatedBy().getId())
                .build();
    }
}
