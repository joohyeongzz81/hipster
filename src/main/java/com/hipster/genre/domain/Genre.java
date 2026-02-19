package com.hipster.genre.domain;

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
@Table(name = "genres", indexes = {
        @Index(name = "idx_genres_name", columnList = "name"),
        @Index(name = "idx_genres_parent", columnList = "parentId"),
        @Index(name = "idx_genres_pending", columnList = "pendingApproval")
})
public class Genre {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column
    private Long parentId;

    @Column
    private Integer level;

    @Column(length = 255)
    private String path;

    @Column(nullable = false)
    @ColumnDefault("false")
    private Boolean isDescriptor = false;

    @Column(columnDefinition = "TEXT")
    private String description;

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
    public Genre(String name, Long parentId, Integer level, String path, Boolean isDescriptor, String description) {
        this.name = name;
        this.parentId = parentId;
        this.level = level;
        this.path = path;
        this.isDescriptor = isDescriptor;
        this.description = description;
    }

    public void approve() {
        this.pendingApproval = false;
    }
}
