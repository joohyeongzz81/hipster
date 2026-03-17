package com.hipster.moderation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "moderation_audit_trail", indexes = {
        @Index(name = "idx_moderation_audit_queue_occurred", columnList = "queue_item_id, occurred_at"),
        @Index(name = "idx_moderation_audit_event_occurred", columnList = "event_type, occurred_at")
})
public class ModerationAuditTrail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "queue_item_id", nullable = false)
    private Long queueItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private ModerationAuditEventType eventType;

    @Column(name = "actor_id")
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 20)
    private ModerationStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", length = 20)
    private ModerationStatus currentStatus;

    @Column(name = "previous_moderator_id")
    private Long previousModeratorId;

    @Column(name = "current_moderator_id")
    private Long currentModeratorId;

    @Column(length = 50)
    private String reason;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    private ModerationAuditTrail(final Long queueItemId,
                                 final ModerationAuditEventType eventType,
                                 final Long actorId,
                                 final ModerationStatus previousStatus,
                                 final ModerationStatus currentStatus,
                                 final Long previousModeratorId,
                                 final Long currentModeratorId,
                                 final String reason,
                                 final String comment,
                                 final LocalDateTime occurredAt) {
        this.queueItemId = queueItemId;
        this.eventType = eventType;
        this.actorId = actorId;
        this.previousStatus = previousStatus;
        this.currentStatus = currentStatus;
        this.previousModeratorId = previousModeratorId;
        this.currentModeratorId = currentModeratorId;
        this.reason = reason;
        this.comment = comment;
        this.occurredAt = occurredAt;
    }

    public static ModerationAuditTrail of(final Long queueItemId,
                                          final ModerationAuditEventType eventType,
                                          final Long actorId,
                                          final ModerationStatus previousStatus,
                                          final ModerationStatus currentStatus,
                                          final Long previousModeratorId,
                                          final Long currentModeratorId,
                                          final String reason,
                                          final String comment,
                                          final LocalDateTime occurredAt) {
        return new ModerationAuditTrail(
                queueItemId,
                eventType,
                actorId,
                previousStatus,
                currentStatus,
                previousModeratorId,
                currentModeratorId,
                reason,
                comment,
                occurredAt
        );
    }
}
