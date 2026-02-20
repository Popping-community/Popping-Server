package com.example.popping.dto;

import jakarta.validation.constraints.NotBlank;

public record BoardUpdateRequest(

        @NotBlank(message = "게시판 이름은 필수입니다.")
        String name,

        @NotBlank(message = "설명은 필수입니다.")
        String description

) {}
