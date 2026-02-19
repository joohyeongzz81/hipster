package com.hipster.artist.dto;

import com.hipster.artist.domain.Artist;

public record ArtistResponse(
        Long id,
        String name,
        String description,
        Integer formedYear,
        String country
) {
    public static ArtistResponse from(Artist artist) {
        return new ArtistResponse(
                artist.getId(),
                artist.getName(),
                artist.getDescription(),
                artist.getFormedYear(),
                artist.getCountry()
        );
    }
}
