package com.hipster.settlement.service;

import com.hipster.reward.domain.RewardLedgerEntry;
import com.hipster.settlement.domain.SettlementAllocation;
import com.hipster.settlement.domain.SettlementRequest;
import com.hipster.settlement.domain.SettlementRequestStatus;
import com.hipster.settlement.domain.SettlementWebhookInbox;
import com.hipster.settlement.dto.request.SettlementWebhookRequest;
import com.hipster.settlement.dto.response.SettlementWebhookReceiveResponse;
import com.hipster.settlement.repository.SettlementAllocationRepository;
import com.hipster.settlement.repository.SettlementRequestRepository;
import com.hipster.settlement.repository.SettlementWebhookInboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SettlementWebhookServiceTest {

    @InjectMocks
    private SettlementWebhookService settlementWebhookService;

    @Mock
    private SettlementWebhookInboxRepository settlementWebhookInboxRepository;

    @Mock
    private SettlementRequestRepository settlementRequestRepository;

    @Mock
    private SettlementAllocationRepository settlementAllocationRepository;

    @Mock
    private SettlementAdjustmentService settlementAdjustmentService;

    @Test
    @DisplayName("성공 웹훅은 unknown 요청을 succeeded로 수렴시킨다")
    void successWebhookConvergesUnknownRequestToSucceeded() {
        final SettlementRequest request = request(11L, 1L, SettlementRequestStatus.UNKNOWN, "provider-ref-success");
        final SettlementWebhookRequest webhookRequest = new SettlementWebhookRequest(
                "mock-payout",
                "evt-1",
                "provider-ref-success",
                "PAYOUT_SUCCEEDED",
                LocalDateTime.now(),
                "hash-1",
                "{\"status\":\"success\"}"
        );

        given(settlementWebhookInboxRepository.findByProviderNameAndProviderEventId("mock-payout", "evt-1"))
                .willReturn(Optional.empty());
        given(settlementRequestRepository.findFirstByProviderReference("provider-ref-success"))
                .willReturn(Optional.of(request));
        given(settlementWebhookInboxRepository.save(any(SettlementWebhookInbox.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        final SettlementWebhookReceiveResponse response = settlementWebhookService.acceptWebhook(webhookRequest);

        assertThat(response.processingStatus()).isEqualTo("PROCESSED");
        assertThat(request.getStatus()).isEqualTo(SettlementRequestStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("성공 이후 실패 웹훅이 오면 요청을 조정 필요 상태로 바꾼다")
    void failureWebhookAfterSuccessMarksNeedsAdjustment() {
        final SettlementRequest request = request(12L, 2L, SettlementRequestStatus.SUCCEEDED, "provider-ref-adjustment");
        final SettlementWebhookRequest webhookRequest = new SettlementWebhookRequest(
                "mock-payout",
                "evt-2",
                "provider-ref-adjustment",
                "PAYOUT_FAILED",
                LocalDateTime.now(),
                "hash-2",
                "{\"status\":\"failed\"}"
        );

        given(settlementWebhookInboxRepository.findByProviderNameAndProviderEventId("mock-payout", "evt-2"))
                .willReturn(Optional.empty());
        given(settlementRequestRepository.findFirstByProviderReference("provider-ref-adjustment"))
                .willReturn(Optional.of(request));
        given(settlementWebhookInboxRepository.save(any(SettlementWebhookInbox.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        settlementWebhookService.acceptWebhook(webhookRequest);

        assertThat(request.getStatus()).isEqualTo(SettlementRequestStatus.NEEDS_ADJUSTMENT);
        verify(settlementAdjustmentService).createOpenDebitAdjustment(
                eq(request),
                eq("Failure webhook received after payout had already succeeded.")
        );
        verify(settlementAllocationRepository, never()).findAllBySettlementRequestId(any());
    }

    @Test
    @DisplayName("중복 웹훅이면 기존 inbox 처리 상태를 그대로 반환한다")
    void duplicateWebhookReturnsExistingProcessingStatus() {
        final SettlementWebhookInbox existingInbox = SettlementWebhookInbox.received(
                "mock-payout",
                "evt-3",
                "provider-ref-duplicate",
                "PAYOUT_SUCCEEDED",
                LocalDateTime.now(),
                "hash-3",
                "{\"status\":\"success\"}"
        );
        existingInbox.markProcessed("Already processed.");

        given(settlementWebhookInboxRepository.findByProviderNameAndProviderEventId("mock-payout", "evt-3"))
                .willReturn(Optional.of(existingInbox));

        final SettlementWebhookReceiveResponse response = settlementWebhookService.acceptWebhook(new SettlementWebhookRequest(
                "mock-payout",
                "evt-3",
                "provider-ref-duplicate",
                "PAYOUT_SUCCEEDED",
                LocalDateTime.now(),
                "hash-3",
                "{\"status\":\"success\"}"
        ));

        assertThat(response.processingStatus()).isEqualTo("PROCESSED");
        verify(settlementWebhookInboxRepository, never()).save(any());
    }

    private SettlementRequest request(final Long id,
                                      final Long userId,
                                      final SettlementRequestStatus status,
                                      final String providerReference) {
        final SettlementRequest request = SettlementRequest.requested(
                "STR-" + id,
                userId,
                "KRW",
                500L,
                500L,
                true,
                "bank-account-snapshot"
        );
        request.markReserved();
        request.markSent("mock-payout", providerReference);
        if (status == SettlementRequestStatus.UNKNOWN) {
            request.markUnknown("mock-payout", providerReference);
        } else if (status == SettlementRequestStatus.SUCCEEDED) {
            request.markSucceeded("mock-payout", providerReference);
        }
        ReflectionTestUtils.setField(request, "id", id);
        return request;
    }
}
