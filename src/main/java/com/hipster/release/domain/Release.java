package com.hipster.release.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "releases", indexes = {
        @Index(name = "idx_releases_title", columnList = "title"),
        @Index(name = "idx_releases_type_date", columnList = "releaseType, releaseDate"),
        @Index(name = "idx_releases_status", columnList = "status")
})
public class Release {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long artistId;

    @Column(name = "genre_id")
    private Long genreId;

    @Column(nullable = false, length = 500)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ReleaseType releaseType;

    @Column(nullable = false)
    private LocalDate releaseDate;

    @Column(length = 100)
    private String catalogNumber;

    @Column(length = 100)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReleaseStatus status = ReleaseStatus.PENDING;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public Release(final Long artistId, final Long genreId, final String title, final ReleaseType releaseType,
                   final LocalDate releaseDate, final String catalogNumber, final String label) {
        this.artistId = artistId;
        this.genreId = genreId;
        this.title = title;
        this.releaseType = releaseType;
        this.releaseDate = releaseDate;
        this.catalogNumber = catalogNumber;
        this.label = label;
    }

    public void approve() {
        this.status = ReleaseStatus.ACTIVE;
    }

    public void delete() {
        this.status = ReleaseStatus.DELETED;
    }
}
