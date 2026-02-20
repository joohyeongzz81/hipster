package com.hipster.global.exception;

public class InvalidTokenException extends BusinessException {

    public InvalidTokenException(final ErrorCode errorCode) {
        super(errorCode);
    }
}
