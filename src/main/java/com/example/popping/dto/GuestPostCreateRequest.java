package com.example.popping.dto;

import jakarta.validation.constraints.NotBlank;

public record GuestPostCreateRequest(

        @NotBlank(message = "제목은 필수입니다.")
        String title,

        @NotBlank(message = "내용은 필수입니다.")
        String content,

        @NotBlank(message = "닉네임은 필수입니다.")
        String guestNickname,

        @NotBlank(message = "비밀번호는 필수입니다.")
        String guestPassword

) {}

