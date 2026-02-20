package com.hipster.global.exception;

public class BadRequestException extends BusinessException {

    public BadRequestException(final ErrorCode errorCode) {
        super(errorCode);
    }
}
