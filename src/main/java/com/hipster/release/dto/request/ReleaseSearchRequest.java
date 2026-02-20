package com.hipster.release.dto.request;

import com.hipster.release.domain.ReleaseType;
import org.springframework.data.domain.Sort;

public record ReleaseSearchRequest(
        String q,
        Long artistId,
        Long genreId,
        ReleaseType releaseType,
        Integer year,
        String sort,
        String order,
        Integer page,
        Integer limit
) {
    public ReleaseSearchRequest {
        if (sort == null) sort = "releaseDate";
        if (order == null) order = "desc";
        if (page == null) page = 1;
        if (limit == null) limit = 20;
        else if (limit > 100) limit = 100;
    }

    public Sort getSortObject() {
        Sort.Direction direction = "asc".equalsIgnoreCase(order) ? Sort.Direction.ASC : Sort.Direction.DESC;
        String property = "releaseDate".equals(sort) || "release_date".equals(sort) ? "releaseDate" : sort;
        if ("title".equals(sort)) property = "title";
        
        return Sort.by(direction, property);
    }
}
