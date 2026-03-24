package com.hipster.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "settlement_webhook_inbox", uniqueConstraints = {
        @UniqueConstraint(name = "uk_settlement_webhook_inbox_provider_event", columnNames = {
                "provider_name", "provider_event_id"
        })
}, indexes = {
        @Index(name = "idx_settlement_webhook_inbox_reference", columnList = "provider_reference"),
        @Index(name = "idx_settlement_webhook_inbox_status", columnList = "processing_status, event_occurred_at")
})
public class SettlementWebhookInbox {

    private static final int MAX_PROCESSING_RESULT_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_name", nullable = false, length = 50, updatable = false)
    private String providerName;

    @Column(name = "provider_event_id", nullable = false, length = 100, updatable = false)
    private String providerEventId;

    @Column(name = "provider_reference", length = 100, updatable = false)
    private String providerReference;

    @Column(name = "event_type", nullable = false, length = 50, updatable = false)
    private String eventType;

    @Column(name = "event_occurred_at", nullable = false, updatable = false)
    private LocalDateTime eventOccurredAt;

    @Column(name = "payload_hash", length = 100, updatable = false)
    private String payloadHash;

    @Column(name = "payload_body", length = 4000, updatable = false)
    private String payloadBody;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 20)
    private SettlementWebhookProcessingStatus processingStatus;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "processing_result", length = MAX_PROCESSING_RESULT_LENGTH)
    private String processingResult;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private SettlementWebhookInbox(final String providerName,
                                   final String providerEventId,
                                   final String providerReference,
                                   final String eventType,
                                   final LocalDateTime eventOccurredAt,
                                   final String payloadHash,
                                   final String payloadBody) {
        this.providerName = Objects.requireNonNull(providerName);
        this.providerEventId = Objects.requireNonNull(providerEventId);
        this.providerReference = providerReference;
        this.eventType = Objects.requireNonNull(eventType);
        this.eventOccurredAt = Objects.requireNonNull(eventOccurredAt);
        this.payloadHash = payloadHash;
        this.payloadBody = payloadBody;
        this.processingStatus = SettlementWebhookProcessingStatus.RECEIVED;
    }

    public static SettlementWebhookInbox received(final String providerName,
                                                  final String providerEventId,
                                                  final String providerReference,
                                                  final String eventType,
                                                  final LocalDateTime eventOccurredAt,
                                                  final String payloadHash,
                                                  final String payloadBody) {
        return new SettlementWebhookInbox(
                providerName,
                providerEventId,
                providerReference,
                eventType,
                eventOccurredAt,
                payloadHash,
                payloadBody
        );
    }

    public void markProcessed(final String resultMessage) {
        this.processingStatus = SettlementWebhookProcessingStatus.PROCESSED;
        this.processedAt = LocalDateTime.now();
        this.processingResult = trimResult(resultMessage);
    }

    public void markDuplicate(final String resultMessage) {
        this.processingStatus = SettlementWebhookProcessingStatus.DUPLICATE;
        this.processedAt = LocalDateTime.now();
        this.processingResult = trimResult(resultMessage);
    }

    public void markFailed(final String resultMessage) {
        this.processingStatus = SettlementWebhookProcessingStatus.FAILED;
        this.processedAt = LocalDateTime.now();
        this.processingResult = trimResult(resultMessage);
    }

    private String trimResult(final String resultMessage) {
        if (resultMessage == null || resultMessage.isBlank()) {
            return null;
        }
        return resultMessage.length() <= MAX_PROCESSING_RESULT_LENGTH
                ? resultMessage
                : resultMessage.substring(0, MAX_PROCESSING_RESULT_LENGTH);
    }
}
