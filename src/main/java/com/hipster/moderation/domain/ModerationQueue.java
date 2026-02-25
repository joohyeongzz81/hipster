package com.hipster.moderation.domain;

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
@Table(name = "moderation_queue", indexes = {
        @Index(name = "idx_moderation_status_priority", columnList = "status, priority, submitted_at"),
        @Index(name = "idx_moderation_submitter", columnList = "submitter_id"),
        @Index(name = "idx_moderation_moderator", columnList = "moderator_id")
})
public class ModerationQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EntityType entityType;

    @Column(nullable = true)
    private Long entityId;

    @Column(nullable = false)
    private Long submitterId;

    @Column(nullable = true)
    private Long moderatorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @ColumnDefault("'PENDING'")
    private ModerationStatus status = ModerationStatus.PENDING;

    @Column(nullable = false)
    @ColumnDefault("2")
    private Integer priority = 2; // Default P2 (Medium)

    @Column(nullable = true, columnDefinition = "TEXT")
    private String metaComment;

    @Column(nullable = true, length = 50)
    private String rejectionReason;

    @Column(nullable = true, columnDefinition = "TEXT")
    private String moderatorComment;

    @Column(nullable = false)
    private LocalDateTime submittedAt;

    @Column(nullable = true)
    private LocalDateTime processedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public ModerationQueue(final EntityType entityType, final Long entityId, final Long submitterId,
                           final String metaComment, final Integer priority) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.submitterId = submitterId;
        this.metaComment = metaComment;
        this.submittedAt = LocalDateTime.now();
        if (priority != null) {
            this.priority = priority;
        }
    }

    // Business Methods
    public void assignModerator(final Long moderatorId) {
        this.moderatorId = moderatorId;
        this.status = ModerationStatus.UNDER_REVIEW;
    }

    public void approve() {
        this.status = ModerationStatus.APPROVED;
        this.processedAt = LocalDateTime.now();
    }

    public void approve(final String comment) {
        this.status = ModerationStatus.APPROVED;
        this.moderatorComment = comment;
        this.processedAt = LocalDateTime.now();
    }

    public void autoApprove() {
        this.status = ModerationStatus.AUTO_APPROVED;
        this.processedAt = LocalDateTime.now();
    }

    public void reject(final RejectionReason reason, final String comment) {
        this.status = ModerationStatus.REJECTED;
        this.rejectionReason = reason.name();
        this.moderatorComment = comment;
        this.processedAt = LocalDateTime.now();
    }
}
