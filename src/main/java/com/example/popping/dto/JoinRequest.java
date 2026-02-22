package com.example.popping.dto;

import jakarta.validation.constraints.NotBlank;

public record JoinRequest(

        @NotBlank(message = "로그인 아이디가 비어있습니다.")
        String loginId,

        @NotBlank(message = "비밀번호가 비어있습니다.")
        String password,

        @NotBlank(message = "닉네임이 비어있습니다.")
        String nickname

) {}
