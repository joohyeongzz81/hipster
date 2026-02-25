package com.hipster.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // 1xxx: HTTP Standard Errors
    BAD_REQUEST(1000, HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    UNAUTHORIZED(1001, HttpStatus.UNAUTHORIZED, "인증되지 않은 사용자입니다."),
    FORBIDDEN(1002, HttpStatus.FORBIDDEN, "접근이 금지되었습니다."),
    NOT_FOUND(1003, HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),
    CONFLICT(1004, HttpStatus.CONFLICT, "충돌이 발생했습니다."),
    TOO_MANY_REQUESTS(1005, HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다."),
    INTERNAL_SERVER_ERROR(1999, HttpStatus.INTERNAL_SERVER_ERROR, "내부 서버 오류입니다."),

    // 2xxx: Auth Domain Custom Errors
    INVALID_TOKEN(2000, HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    AUTHORIZATION_HEADER_MISSING(2001, HttpStatus.UNAUTHORIZED, "인증 헤더가 없거나 잘못된 형식입니다."),
    INVALID_TOKEN_CLAIMS(2002, HttpStatus.UNAUTHORIZED, "토큰의 클레임이 유효하지 않습니다."),
    INVALID_OR_MISSING_TOKEN(2003, HttpStatus.UNAUTHORIZED, "토큰이 없거나 유효하지 않습니다."),
    INSUFFICIENT_PERMISSIONS(2004, HttpStatus.FORBIDDEN, "요청을 수행할 수 있는 권한이 없습니다."),
    INVALID_PASSWORD(2005, HttpStatus.UNAUTHORIZED, "비밀번호가 일치하지 않습니다."),
    REFRESH_TOKEN_EXPIRED(2006, HttpStatus.UNAUTHORIZED, "리프레시 토큰이 만료되었습니다."),
    EXPIRED_TOKEN(2007, HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다."),
    ACCESS_DENIED(2008, HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

    // 3xxx: Artist Domain Custom Errors
    ARTIST_NOT_FOUND(3000, HttpStatus.NOT_FOUND, "아티스트를 찾을 수 없습니다."),

    // 4xxx: User & Token Domain Custom Errors
    USER_NOT_FOUND(4000, HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    EMAIL_ALREADY_EXISTS(4001, HttpStatus.CONFLICT, "이미 사용중인 이메일입니다."),
    USERNAME_ALREADY_EXISTS(4002, HttpStatus.CONFLICT, "이미 사용중인 사용자 이름입니다."),
    REFRESH_TOKEN_NOT_FOUND(4003, HttpStatus.NOT_FOUND, "리프레시 토큰을 찾을 수 없습니다."),
    RELEASE_NOT_FOUND(4004, HttpStatus.NOT_FOUND, "앨범을 찾을 수 없습니다."),
    ALREADY_UNDER_REVIEW(4009, HttpStatus.CONFLICT, "이미 다른 모더레이터가 검토 중입니다."),

    // 5xxx: Chart Domain Custom Errors
    INVALID_CHART_LIMIT(5000, HttpStatus.BAD_REQUEST, "차트 조회 개수는 10 이상 1000 이하여야 합니다."),

    // 6xxx: Moderation Domain Custom Errors
    MODERATION_ITEM_NOT_FOUND(6000, HttpStatus.NOT_FOUND, "모더레이션 항목을 찾을 수 없습니다."),
    MODERATION_NOT_CLAIMED(6001, HttpStatus.FORBIDDEN, "먼저 해당 항목을 담당해야 합니다."),
    MODERATION_CLAIMED_BY_OTHER(6002, HttpStatus.FORBIDDEN, "다른 모더레이터가 담당 중인 항목입니다."),
    MODERATION_ALREADY_PROCESSED(6003, HttpStatus.BAD_REQUEST, "이미 처리 완료된 모더레이션 항목입니다."),

    // 7xxx: Rating Domain Custom Errors
    INVALID_RATING_SCORE(7000, HttpStatus.BAD_REQUEST, "점수는 0.5~5.0 범위의 0.5 단위여야 합니다."),

    // 8xxx: Review Domain Custom Errors
    REVIEW_NOT_FOUND(8000, HttpStatus.NOT_FOUND, "리뷰를 찾을 수 없습니다."),
    REVIEW_NOT_OWNER(8001, HttpStatus.FORBIDDEN, "리뷰 작성자만 수정/삭제할 수 있습니다.");

    private final int code;
    private final HttpStatus status;
    private final String message;
}
