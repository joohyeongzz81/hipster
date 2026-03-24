package com.hipster.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementRequestStateMachineTest {

    @Test
    @DisplayName("정산 요청은 reserved -> sent -> unknown -> succeeded로 수렴할 수 있다")
    void requestCanConvergeFromUnknownToSucceeded() {
        final SettlementRequest request = SettlementRequest.requested(
                "STR-REQUEST-001",
                1L,
                "KRW",
                500L,
                500L,
                true,
                "bank-account-snapshot"
        );

        request.markReserved();
        request.markSent("mock-payout", "provider-ref-1");
        request.markUnknown("mock-payout", "provider-ref-1");
        request.markSucceeded("mock-payout", "provider-ref-1");

        assertThat(request.getStatus()).isEqualTo(SettlementRequestStatus.SUCCEEDED);
        assertThat(request.getProviderName()).isEqualTo("mock-payout");
        assertThat(request.getProviderReference()).isEqualTo("provider-ref-1");
        assertThat(request.getOpenRequestUserId()).isNull();
        assertThat(request.getSentAt()).isNotNull();
        assertThat(request.getFinalizedAt()).isNotNull();
    }

    @Test
    @DisplayName("지급 성공 이후에는 failed로 되돌릴 수 없다")
    void succeededRequestCannotBecomeFailed() {
        final SettlementRequest request = SettlementRequest.requested(
                "STR-REQUEST-002",
                2L,
                "KRW",
                900L,
                900L,
                true,
                "bank-account-snapshot"
        );

        request.markReserved();
        request.markSent("mock-payout", "provider-ref-2");
        request.markSucceeded("mock-payout", "provider-ref-2");

        assertThatThrownBy(() -> request.markFailed("mock-payout", "provider-ref-2"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("열린 정산 요청 표식은 open 상태에서는 유지되고 finalized 되면 지워진다")
    void openRequestMarkerIsClearedWhenRequestFinalizes() {
        final SettlementRequest request = SettlementRequest.requested(
                "STR-REQUEST-003",
                3L,
                "KRW",
                300L,
                300L,
                true,
                "bank-account-snapshot"
        );

        assertThat(request.getOpenRequestUserId()).isEqualTo(3L);

        request.markReserved();
        request.markSent("mock-payout", "provider-ref-3");
        assertThat(request.getOpenRequestUserId()).isEqualTo(3L);

        request.markFailed("mock-payout", "provider-ref-3");
        assertThat(request.getOpenRequestUserId()).isNull();
    }
}
