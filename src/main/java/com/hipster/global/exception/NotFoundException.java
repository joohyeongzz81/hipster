package com.hipster.global.exception;

public class NotFoundException extends BusinessException {

    public NotFoundException(final ErrorCode errorCode) {
        super(errorCode);
    }
}
