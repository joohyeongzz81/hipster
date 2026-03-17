package com.hipster.moderation.domain;

public enum ModerationAuditEventType {
    CLAIMED,
    UNCLAIMED,
    LEASE_EXPIRED,
    REASSIGNED,
    APPROVED,
    REJECTED
}
