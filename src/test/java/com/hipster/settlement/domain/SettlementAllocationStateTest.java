package com.hipster.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementAllocationStateTest {

    @Test
    @DisplayName("allocation release는 active marker를 지워서 같은 reward entry가 다시 예약될 수 있게 한다")
    void releaseClearsActiveReservationMarker() {
        final SettlementAllocation allocation = SettlementAllocation.reserve(11L, 101L, 1L, 700L);

        assertThat(allocation.isActive()).isTrue();
        assertThat(allocation.getActiveRewardLedgerEntryId()).isEqualTo(101L);

        allocation.release();

        assertThat(allocation.isActive()).isFalse();
        assertThat(allocation.getAllocationType()).isEqualTo(SettlementAllocationType.RELEASE);
        assertThat(allocation.getActiveRewardLedgerEntryId()).isNull();
    }
}
