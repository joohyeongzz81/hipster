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
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "releases")
public class Release {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long artistId;

    @Column(name = "location_id")
    private Long locationId;

    @Column(nullable = false, length = 500)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ReleaseType releaseType;

    @Column(nullable = false)
    private LocalDate releaseDate;

    @Column(length = 100)
    private String catalogNumber;

    @Column(columnDefinition = "TEXT")
    private String label;

    @OneToMany(mappedBy = "release", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReleaseGenre> releaseGenres = new ArrayList<>();

    @OneToMany(mappedBy = "release", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReleaseDescriptor> releaseDescriptors = new ArrayList<>();

    @OneToMany(mappedBy = "release", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReleaseLanguage> releaseLanguages = new ArrayList<>();

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
    public Release(final Long artistId, final Long locationId, final String title, final ReleaseType releaseType,
                   final LocalDate releaseDate, final String catalogNumber, final String label) {
        this.artistId = artistId;
        this.locationId = locationId;
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
