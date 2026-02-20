package com.hipster.track.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "tracks", indexes = {
        @Index(name = "idx_tracks_release", columnList = "releaseId"),
        @Index(name = "idx_tracks_release_number", columnList = "releaseId, trackNumber")
})
public class Track {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long releaseId;

    @Column(nullable = false)
    private Integer trackNumber;

    @Column(nullable = false, length = 255)
    private String title;

    @Column
    private Integer durationSeconds;

    @Builder
    public Track(final Long releaseId, final Integer trackNumber, final String title, final Integer durationSeconds) {
        this.releaseId = releaseId;
        this.trackNumber = trackNumber;
        this.title = title;
        this.durationSeconds = durationSeconds;
    }
}
