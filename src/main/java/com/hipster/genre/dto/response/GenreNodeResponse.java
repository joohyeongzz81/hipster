package com.hipster.genre.dto.response;

import com.hipster.genre.domain.Genre;
import java.util.ArrayList;
import java.util.List;

public record GenreNodeResponse(
        Long id,
        String name,
        Long parentId,
        Integer level,
        String path,
        List<GenreNodeResponse> children
) {
    public static GenreNodeResponse from(final Genre genre) {
        return new GenreNodeResponse(
                genre.getId(),
                genre.getName(),
                genre.getParentId(),
                genre.getLevel(),
                genre.getPath(),
                new ArrayList<>()
        );
    }
}
