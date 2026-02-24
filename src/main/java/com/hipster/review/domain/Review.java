package com.hipster.review.domain;

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
@Table(name = "reviews", indexes = {
        @Index(name = "idx_reviews_user", columnList = "userId"),
        @Index(name = "idx_reviews_release", columnList = "releaseId"),
        @Index(name = "idx_reviews_status", columnList = "status"),
        @Index(name = "idx_reviews_covering", columnList = "userId, createdAt")
})
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long releaseId;

    @Lob
    @Column(nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @ColumnDefault("'ACTIVE'")
    private ReviewStatus status = ReviewStatus.ACTIVE;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public Review(final Long userId, final Long releaseId, final String content) {
        this.userId = userId;
        this.releaseId = releaseId;
        this.content = content;
    }

    public void updateContent(final String content) {
        this.content = content;
    }

    public void delete() {
        this.status = ReviewStatus.DELETED;
    }
}
