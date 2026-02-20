package com.hipster.global.exception;

public class QuotaExceededException extends BusinessException {

    public QuotaExceededException(final ErrorCode errorCode) {
        super(errorCode);
    }
}
