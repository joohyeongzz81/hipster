package com.hipster.global.exception;

public class UnauthorizedException extends BusinessException {

    public UnauthorizedException(final ErrorCode errorCode) {
        super(errorCode);
    }
}
