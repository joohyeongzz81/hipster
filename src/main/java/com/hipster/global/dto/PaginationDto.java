package com.hipster.global.dto;

public record PaginationDto(
        Integer page,
        Integer limit,
        Long totalItems,
        Integer totalPages
) {
}
