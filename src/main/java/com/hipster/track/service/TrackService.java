package com.hipster.track.service;

import com.hipster.track.domain.Track;
import com.hipster.track.dto.response.TrackResponse;
import com.hipster.track.repository.TrackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrackService {

    private final TrackRepository trackRepository;

    public List<TrackResponse> getTracksByReleaseId(final Long releaseId) {
        return trackRepository.findByReleaseIdOrderByTrackNumberAsc(releaseId).stream()
                .map(TrackResponse::from)
                .toList();
    }
}
