package com.hipster.auth.jwt;

import com.hipster.global.exception.BusinessException;
import com.hipster.global.exception.ErrorCode;

public class InvalidTokenException extends BusinessException {

    public InvalidTokenException() {
        super(ErrorCode.UNAUTHORIZED, "Invalid token");
    }

    public InvalidTokenException(String message) {
        super(ErrorCode.UNAUTHORIZED, message);
    }
}
