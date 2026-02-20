package com.hipster.global.exception;

import lombok.Getter;

@Getter
public abstract class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(final ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
