package com.hipster.global.exception;

public class ConflictException extends BusinessException {

    public ConflictException(final ErrorCode errorCode) {
        super(errorCode);
    }
}
