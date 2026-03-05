package com.example.popping.dto;

import java.util.List;

public record BoardPageResponse(

        List<BoardResponse> boards,
        int totalBoards,
        int currentPage,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious

) {
    public BoardPageResponse {
        boards = boards == null ? List.of() : List.copyOf(boards);
    }
}
