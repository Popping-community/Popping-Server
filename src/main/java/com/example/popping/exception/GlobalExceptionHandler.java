package com.example.popping.exception;

import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomAppException.class)
    public String handleCustomAppException(Model model, CustomAppException e) {
        log.error("CustomAppException: {}", e.getMessage());
        model.addAttribute("errorCode", e.getErrorType().name());
        model.addAttribute("message",
                (e.getCustomMessage() != null) ? e.getCustomMessage() : e.getErrorType().getMessage());
        return "error/custom-error";
    }
}
