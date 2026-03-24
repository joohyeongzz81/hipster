package com.hipster.settlement.domain;

public enum SettlementRequestStatus {
    REQUESTED,
    RESERVED,
    SENT,
    UNKNOWN,
    SUCCEEDED,
    FAILED,
    NEEDS_ADJUSTMENT;

    public boolean isOpen() {
        return this == REQUESTED
                || this == RESERVED
                || this == SENT
                || this == UNKNOWN;
    }

    public boolean isFinalized() {
        return this == SUCCEEDED
                || this == FAILED
                || this == NEEDS_ADJUSTMENT;
    }
}
