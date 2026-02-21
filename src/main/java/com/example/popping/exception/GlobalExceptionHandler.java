package com.example.popping.exception;

import org.springframework.dao.DataIntegrityViolationException;
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

    @ExceptionHandler(IllegalArgumentException.class)
    public String handleIllegalArgument(Model model, IllegalArgumentException e) {
        log.warn("IllegalArgumentException: {}", e.getMessage());
        model.addAttribute("errorCode", ErrorType.VALIDATION_ERROR.name());
        model.addAttribute("message", e.getMessage());
        return "error/custom-error";
    }


    @ExceptionHandler(DataIntegrityViolationException.class)
    public String handleDataIntegrity(Model model, DataIntegrityViolationException e) {
        log.error("DataIntegrityViolationException", e);

        ErrorType type = ErrorType.VALIDATION_ERROR;
        String message = "요청을 처리할 수 없습니다.";

        String root = getRootMessage(e);
        if (root != null) {
            if (root.contains("login_id")) {
                type = ErrorType.DUPLICATE_LOGIN_ID;
                message = "이미 사용 중인 아이디입니다.";
            } else if (root.contains("nickname")) {
                type = ErrorType.DUPLICATE_NICKNAME;
                message = "이미 사용 중인 닉네임입니다.";
            } else if (root.contains("uk_board_slug")) {
                type = ErrorType.BOARD_SLUG_DUPLICATED;
                message = "이미 사용 중인 게시판 주소(slug)입니다.";
            }
        }

        model.addAttribute("errorCode", type.name());
        model.addAttribute("message", message);
        return "error/custom-error";
    }

    @ExceptionHandler(Exception.class)
    public String handleException(Model model, Exception e) {
        log.error("Unhandled Exception", e);
        model.addAttribute("errorCode", ErrorType.INTERNAL_ERROR.name());
        model.addAttribute("message", ErrorType.INTERNAL_ERROR.getMessage());
        return "error/custom-error";
    }

    private String getRootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur.getMessage();
    }
}
