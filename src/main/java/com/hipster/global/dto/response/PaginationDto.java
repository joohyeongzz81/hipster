package com.hipster.global.dto.response;

public record PaginationDto(
        Integer page,
        Integer limit,
        Long totalItems,
        Integer totalPages
) {
}
