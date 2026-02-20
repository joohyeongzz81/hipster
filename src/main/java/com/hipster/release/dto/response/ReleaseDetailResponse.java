package com.hipster.release.dto.response;

import com.hipster.release.domain.ReleaseType;
import com.hipster.track.dto.response.TrackResponse;
import java.time.LocalDate;
import java.util.List;

public record ReleaseDetailResponse(
        Long id,
        String title,
        Long artistId,
        String artistName,
        ReleaseType releaseType,
        LocalDate releaseDate,
        String catalogNumber,
        String label,
        Double averageRating,
        Integer totalRatings,
        List<TrackResponse> tracks
) {
}
