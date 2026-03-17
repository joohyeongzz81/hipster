package com.hipster.reward.domain;

public enum RewardApprovalAccrualState {
    NOT_ELIGIBLE,
    MISSING,
    ACCRUED,
    PARTICIPATION_BLOCKED,
    CAP_EXCEEDED,
    REVERSED
}
