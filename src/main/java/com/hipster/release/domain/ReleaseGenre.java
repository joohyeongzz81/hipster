package com.hipster.release.domain;

import com.hipster.genre.domain.Genre;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "release_genres")
public class ReleaseGenre {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "release_id", nullable = false)
    private Release release;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "genre_id", nullable = false)
    private Genre genre;

    @Column(nullable = false)
    private Boolean isPrimary = false;

    @Column(name = "`order`", nullable = false)
    private Integer order = 0;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public ReleaseGenre(final Release release, final Genre genre, final Boolean isPrimary, final Integer order) {
        this.release = release;
        this.genre = genre;
        if (isPrimary != null) {
            this.isPrimary = isPrimary;
        }
        if (order != null) {
            this.order = order;
        }
    }
}
