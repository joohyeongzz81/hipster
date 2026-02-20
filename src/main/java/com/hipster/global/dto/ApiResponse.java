package com.hipster.global.dto;

public record ApiResponse<T>(
        int status,
        String message,
        T data) {
    public static <T> ApiResponse<T> ok(final T data) {
        return new ApiResponse<>(200, "Success", data);
    }

    public static <T> ApiResponse<T> of(final int status, final String message, final T data) {
        return new ApiResponse<>(status, message, data);
    }
}
