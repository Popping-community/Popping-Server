package com.example.popping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GuestCommentCreateRequest(

        @NotBlank(message = "내용은 필수입니다.")
        @Size(min = 1, max = 500, message = "내용은 1자 이상 500자 이하여야 합니다.")
        String content,

        @NotBlank(message = "닉네임은 필수입니다.")
        String guestNickname,

        @NotBlank(message = "비밀번호는 필수입니다.")
        String guestPassword

) {}
