package com.example.popping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MemberPostUpdateRequest(

        @NotBlank(message = "제목은 필수입니다.")
        @Size(max = 200, message = "제목은 200자 이하입니다.")
        String title,

        @NotBlank(message = "내용은 필수입니다.")
        String content

) {}
