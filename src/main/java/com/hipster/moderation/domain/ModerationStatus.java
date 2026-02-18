package com.hipster.moderation.domain;

public enum ModerationStatus {
    PENDING,
    AUTO_APPROVED,
    MODERATOR_REVIEW,
    UNDER_REVIEW,
    APPROVED,
    REJECTED
}
