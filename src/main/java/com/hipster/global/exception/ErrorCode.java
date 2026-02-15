package com.hipster.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증되지 않은 사용자입니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근이 금지되었습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),
    CONFLICT(HttpStatus.CONFLICT, "충돌이 발생했습니다."),
    QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "할당량이 초과되었습니다."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    AUTHORIZATION_HEADER_MISSING(HttpStatus.UNAUTHORIZED, "인증 헤더가 없거나 잘못된 형식입니다."),
    INVALID_TOKEN_CLAIMS(HttpStatus.UNAUTHORIZED, "토큰의 클레임이 유효하지 않습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "내부 서버 오류입니다.");

    private final HttpStatus status;
    private final String message;
}
