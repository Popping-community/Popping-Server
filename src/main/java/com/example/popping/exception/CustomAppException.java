package com.example.popping.exception;

import lombok.Getter;

@Getter
public class CustomAppException extends RuntimeException {
    private final ErrorType errorType;
    private final String customMessage;

    public CustomAppException(ErrorType errorType) {
        super(errorType.getMessage());
        this.errorType = errorType;
        this.customMessage = null;
    }

    public CustomAppException(ErrorType errorType, String customMessage) {
        super(errorType.getMessage());
        this.errorType = errorType;
        this.customMessage = customMessage;
    }
}
