package com.hipster.track.repository;

import com.hipster.track.domain.Track;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrackRepository extends JpaRepository<Track, Long> {

    List<Track> findByReleaseIdOrderByTrackNumberAsc(Long releaseId);
}
