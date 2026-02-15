package com.hipster.global.exception;

public class InvalidTokenException extends BusinessException {

    public InvalidTokenException(ErrorCode errorCode) {
        super(errorCode);
    }
}
