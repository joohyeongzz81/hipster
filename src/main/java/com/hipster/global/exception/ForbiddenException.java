package com.hipster.global.exception;

public class ForbiddenException extends BusinessException {

    public ForbiddenException(final ErrorCode errorCode) {
        super(errorCode);
    }
}
