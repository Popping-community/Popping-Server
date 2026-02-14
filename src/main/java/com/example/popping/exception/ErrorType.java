package com.example.popping.exception;

import org.springframework.http.HttpStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorType {
    //400
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청값이 올바르지 않습니다."),
    //401
    NO_AUTHENTICATION(HttpStatus.UNAUTHORIZED, "인증되지 않은 사용자입니다."),
    //403
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    //404
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    USERNAME_DUPLICATED(HttpStatus.NOT_FOUND, "이미 사용중인 아이디입니다."),
    NICKNAME_DUPLICATED(HttpStatus.NOT_FOUND, "이미 사용중인 닉네임입니다."),
    POST_NOT_FOUND(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."),
    BOARD_NOT_FOUND(HttpStatus.NOT_FOUND, "게시판을 찾을 수 없습니다."),
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다."),
    EMPTY_IMAGE_FILE(HttpStatus.NOT_FOUND, "이미지 파일이 비어있습니다."),
    NO_FILE_EXTENTION(HttpStatus.NOT_FOUND, "파일 확장자가 없습니다. 올바른 이미지 파일을 업로드해주세요."),
    INVALID_FILE_EXTENTION(HttpStatus.NOT_FOUND, "올바른 이미지 파일이 아닙니다. 지원하는 확장자는 jpg, jpeg, png입니다."),
    INVALID_TARGET_TYPE(HttpStatus.NOT_FOUND, "잘못된 대상 타입입니다. 지원하는 타입은 POST, COMMENT입니다."),
    DUPLICATE_LOGIN_ID(HttpStatus.NOT_FOUND, "이미 사용 중인 아이디입니다."),
    DUPLICATE_NICKNAME(HttpStatus.NOT_FOUND, "이미 사용 중인 닉네임입니다."),
    //500
    IO_EXCEPTION_ON_IMAGE_UPLOAD(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 업로드 중 IO 예외가 발생했습니다."),
    PUT_OBJECT_EXCEPTION(HttpStatus.INTERNAL_SERVER_ERROR, "S3에 이미지를 업로드하는 중 문제가 발생했습니다."),
    IO_EXCEPTION_ON_IMAGE_DELETE(HttpStatus.INTERNAL_SERVER_ERROR, "이미지 삭제 중 IO 예외가 발생했습니다."),
    TEMP_IMAGE_DELETE_EXCEPTION(HttpStatus.INTERNAL_SERVER_ERROR, "임시 이미지 삭제 중 문제가 발생했습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버에 문제가 발생했습니다. 잠시만 기달려주세요.");


    private final HttpStatus httpStatus;

    private final String message;

    public int getStatusCode() {
        return httpStatus.value();
    }
}
