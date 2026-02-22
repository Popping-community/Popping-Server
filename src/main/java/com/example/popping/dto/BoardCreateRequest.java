package com.example.popping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record BoardCreateRequest(

        @NotBlank(message = "게시판 이름은 필수입니다.")
        String name,

        @NotBlank(message = "설명은 필수입니다.")
        String description,

        @Pattern(
                regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                message = "슬러그 형식이 올바르지 않습니다."
        )
        String slug

) {}
