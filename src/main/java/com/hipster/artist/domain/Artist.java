package com.hipster.artist.domain;

import com.hipster.artist.dto.CreateArtistRequest;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "artists", indexes = {
        @Index(name = "idx_artists_name", columnList = "name"),
        @Index(name = "idx_artists_status", columnList = "status")
})
public class Artist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column
    private Integer formedYear;

    @Column(length = 100)
    private String country;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ArtistStatus status = ArtistStatus.PENDING;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Artist(final String name, final String description, final Integer formedYear, final String country) {
        this.name = name;
        this.description = description;
        this.formedYear = formedYear;
        this.country = country;
    }

    public static Artist from(final CreateArtistRequest request) {
        return Artist.builder()
                .name(request.name())
                .description(request.description())
                .formedYear(request.formedYear())
                .country(request.country())
                .build();
    }

    public void approve() {
        this.status = ArtistStatus.ACTIVE;
    }

    public void delete() {
        this.status = ArtistStatus.DELETED;
    }
}
