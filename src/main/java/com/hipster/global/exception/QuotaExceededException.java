package com.hipster.global.exception;

public class QuotaExceededException extends BusinessException {

    public QuotaExceededException(ErrorCode errorCode) {
        super(errorCode);
    }
}
