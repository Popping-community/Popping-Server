package com.example.popping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GuestPostUpdateRequest(

        @NotBlank(message = "제목은 필수입니다.")
        @Size(max = 200, message = "제목은 200자 이하입니다.")
        String title,

        @NotBlank(message = "내용은 필수입니다.")
        String content,

        @NotBlank(message = "닉네임은 필수입니다.")
        String guestNickname,

        @NotBlank(message = "비밀번호는 필수입니다.")
        String guestPassword

) {}
