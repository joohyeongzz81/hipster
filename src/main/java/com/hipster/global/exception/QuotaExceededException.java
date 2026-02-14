package com.hipster.global.exception;

public class QuotaExceededException extends BusinessException {

    public QuotaExceededException() {
        super(ErrorCode.QUOTA_EXCEEDED);
    }

    public QuotaExceededException(String message) {
        super(ErrorCode.QUOTA_EXCEEDED, message);
    }
}
