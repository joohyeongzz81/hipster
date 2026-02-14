package com.hipster.global.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.util.Map;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private final String error;
    private final String message;
    private Map<String, Object> details;

    public ErrorResponse(ErrorCode errorCode) {
        this.error = errorCode.name();
        this.message = errorCode.getMessage();
    }

    public ErrorResponse(ErrorCode errorCode, String message) {
        this.error = errorCode.name();
        this.message = message;
    }
    
    public ErrorResponse(ErrorCode errorCode, Map<String, Object> details) {
        this.error = errorCode.name();
        this.message = errorCode.getMessage();
        this.details = details;
    }
}
