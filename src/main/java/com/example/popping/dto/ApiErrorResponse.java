package com.example.popping.dto;

import java.util.Map;

public record ApiErrorResponse(
        String errorCode,
        String message,
        Map<String, String> fieldErrors
) {
    public ApiErrorResponse(String errorCode, String message) {
        this(errorCode, message, null);
    }
}
