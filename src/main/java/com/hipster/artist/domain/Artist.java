package com.hipster.artist.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "artists", indexes = {
        @Index(name = "idx_artists_name", columnList = "name"),
        @Index(name = "idx_artists_pending", columnList = "pendingApproval")
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

    @Column(nullable = false)
    @ColumnDefault("true")
    private Boolean pendingApproval = true;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public Artist(String name, String description, Integer formedYear, String country) {
        this.name = name;
        this.description = description;
        this.formedYear = formedYear;
        this.country = country;
    }

    public void approve() {
        this.pendingApproval = false;
    }
}
