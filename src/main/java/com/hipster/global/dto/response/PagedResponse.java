package com.hipster.global.dto.response;

import java.util.List;

public record PagedResponse<T>(
        List<T> data,
        PaginationDto pagination
) {
}
