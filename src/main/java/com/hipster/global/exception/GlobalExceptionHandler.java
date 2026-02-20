package com.hipster.global.exception;

import com.hipster.global.dto.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(final MethodArgumentNotValidException e) {
        final String detailedErrors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> String.format("'%s': %s", error.getField(), error.getDefaultMessage()))
                .collect(Collectors.joining(", "));
        log.warn("Handle MethodArgumentNotValidException: [{}]", detailedErrors, e);
        return new ResponseEntity<>(
                ApiResponse.of(ErrorCode.BAD_REQUEST.getCode(), ErrorCode.BAD_REQUEST.getMessage(), null),
                ErrorCode.BAD_REQUEST.getStatus());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(final BusinessException e) {
        log.warn("Handle BusinessException: {} ({})", e.getErrorCode().getMessage(), e.getErrorCode().getCode(), e);
        return new ResponseEntity<>(
                ApiResponse.of(e.getErrorCode().getCode(), e.getErrorCode().getMessage(), null),
                e.getErrorCode().getStatus());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(final Exception e) {
        log.error("Handle Exception", e);
        return new ResponseEntity<>(
                ApiResponse.of(ErrorCode.INTERNAL_SERVER_ERROR.getCode(), ErrorCode.INTERNAL_SERVER_ERROR.getMessage(),
                        null),
                ErrorCode.INTERNAL_SERVER_ERROR.getStatus());
    }
}
