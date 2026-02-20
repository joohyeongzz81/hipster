package com.hipster.track.dto;

import com.hipster.track.domain.Track;

public record TrackResponse(
        Long id,
        Integer trackNumber,
        String title,
        Integer durationSeconds
) {
    public static TrackResponse from(final Track track) {
        return new TrackResponse(
                track.getId(),
                track.getTrackNumber(),
                track.getTitle(),
                track.getDurationSeconds()
        );
    }
}
