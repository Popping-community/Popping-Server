package com.example.popping.dto;

import jakarta.validation.constraints.NotBlank;

public record GuestPasswordRequest(
        @NotBlank(message = "비밀번호는 필수입니다.")
        String password
) {}
