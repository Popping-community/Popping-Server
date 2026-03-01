package com.example.popping.exception;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import lombok.extern.slf4j.Slf4j;

import com.example.popping.dto.ApiErrorResponse;

@Slf4j
@RestControllerAdvice(basePackages = "com.example.popping.controller.api")
public class ApiExceptionHandler {

    @ExceptionHandler(CustomAppException.class)
    public ResponseEntity<ApiErrorResponse> handleCustomAppException(CustomAppException e) {
        ErrorType type = e.getErrorType();

        String message = (e.getCustomMessage() != null && !e.getCustomMessage().isBlank())
                ? e.getCustomMessage()
                : type.getMessage();

        log.warn("CustomAppException(API) type={}, msg={}", type.name(), message);

        return ResponseEntity
                .status(type.getHttpStatus())
                .body(new ApiErrorResponse(type.name(), message));
    }

    // @Valid DTO 검증 실패 (RequestBody)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        fe -> fe.getField(),
                        fe -> fe.getDefaultMessage(),
                        (a, b) -> a // 중복 키 방지
                ));

        log.warn("Validation failed(API): {}", fieldErrors);

        return ResponseEntity
                .status(ErrorType.VALIDATION_ERROR.getHttpStatus())
                .body(new ApiErrorResponse(
                        ErrorType.VALIDATION_ERROR.name(),
                        ErrorType.VALIDATION_ERROR.getMessage(),
                        fieldErrors
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("IllegalArgumentException(API): {}", e.getMessage());

        return ResponseEntity
                .status(ErrorType.VALIDATION_ERROR.getHttpStatus())
                .body(new ApiErrorResponse(
                        ErrorType.VALIDATION_ERROR.name(),
                        e.getMessage()
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleException(Exception e) {
        log.error("Unhandled Exception(API)", e);

        return ResponseEntity
                .status(ErrorType.INTERNAL_ERROR.getHttpStatus())
                .body(new ApiErrorResponse(
                        ErrorType.INTERNAL_ERROR.name(),
                        ErrorType.INTERNAL_ERROR.getMessage()
                ));
    }
}
