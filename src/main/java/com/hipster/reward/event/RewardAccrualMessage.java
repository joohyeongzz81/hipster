package com.hipster.reward.event;

public record RewardAccrualMessage(Long outboxId, Long approvalId, String campaignCode) {
}
