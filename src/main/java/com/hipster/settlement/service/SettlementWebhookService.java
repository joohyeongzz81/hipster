package com.hipster.settlement.service;

import com.hipster.settlement.domain.SettlementAllocation;
import com.hipster.settlement.domain.SettlementRequest;
import com.hipster.settlement.domain.SettlementRequestStatus;
import com.hipster.settlement.domain.SettlementWebhookInbox;
import com.hipster.settlement.dto.request.SettlementWebhookRequest;
import com.hipster.settlement.dto.response.SettlementWebhookReceiveResponse;
import com.hipster.settlement.repository.SettlementAllocationRepository;
import com.hipster.settlement.repository.SettlementRequestRepository;
import com.hipster.settlement.repository.SettlementWebhookInboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SettlementWebhookService {

    private final SettlementWebhookInboxRepository settlementWebhookInboxRepository;
    private final SettlementRequestRepository settlementRequestRepository;
    private final SettlementAllocationRepository settlementAllocationRepository;
    private final SettlementAdjustmentService settlementAdjustmentService;

    @Transactional
    public SettlementWebhookReceiveResponse acceptWebhook(final SettlementWebhookRequest request) {
        return settlementWebhookInboxRepository.findByProviderNameAndProviderEventId(
                        request.providerName(),
                        request.providerEventId()
                )
                .map(existing -> new SettlementWebhookReceiveResponse(
                        existing.getProviderName(),
                        existing.getProviderEventId(),
                        existing.getProcessingStatus().name()
                ))
                .orElseGet(() -> saveReceivedWebhook(request));
    }

    private SettlementWebhookReceiveResponse saveReceivedWebhook(final SettlementWebhookRequest request) {
        final SettlementWebhookInbox inbox = SettlementWebhookInbox.received(
                request.providerName(),
                request.providerEventId(),
                request.providerReference(),
                request.eventType(),
                request.eventOccurredAt(),
                request.payloadHash(),
                request.payloadBody()
        );
        applyWebhookToSettlementRequest(request, inbox);
        final SettlementWebhookInbox savedInbox = settlementWebhookInboxRepository.save(inbox);
        return new SettlementWebhookReceiveResponse(
                savedInbox.getProviderName(),
                savedInbox.getProviderEventId(),
                savedInbox.getProcessingStatus().name()
        );
    }

    private void applyWebhookToSettlementRequest(final SettlementWebhookRequest request,
                                                 final SettlementWebhookInbox inbox) {
        if (request.providerReference() == null || request.providerReference().isBlank()) {
            inbox.markProcessed("Webhook stored without provider reference.");
            return;
        }

        final SettlementRequest settlementRequest = settlementRequestRepository.findFirstByProviderReference(request.providerReference())
                .orElse(null);
        if (settlementRequest == null) {
            inbox.markProcessed("Webhook stored without matching settlement request.");
            return;
        }

        final String normalizedEventType = request.eventType().toUpperCase(Locale.ROOT);
        if (isSuccessEvent(normalizedEventType)) {
            handleSuccessWebhook(settlementRequest, inbox);
            return;
        }
        if (isFailureEvent(normalizedEventType)) {
            handleFailureWebhook(settlementRequest, inbox);
            return;
        }

        inbox.markProcessed("Webhook stored without settlement state change.");
    }

    private void handleSuccessWebhook(final SettlementRequest settlementRequest,
                                      final SettlementWebhookInbox inbox) {
        if (settlementRequest.getStatus() == SettlementRequestStatus.SENT
                || settlementRequest.getStatus() == SettlementRequestStatus.UNKNOWN) {
            settlementRequest.markSucceeded(inbox.getProviderName(), inbox.getProviderReference());
            inbox.markProcessed("Settlement request marked as succeeded.");
            return;
        }

        inbox.markProcessed("Success webhook ignored for current settlement request status.");
    }

    private void handleFailureWebhook(final SettlementRequest settlementRequest,
                                      final SettlementWebhookInbox inbox) {
        if (settlementRequest.getStatus() == SettlementRequestStatus.SUCCEEDED) {
            settlementRequest.markNeedsAdjustment();
            settlementAdjustmentService.createOpenDebitAdjustment(
                    settlementRequest,
                    "Failure webhook received after payout had already succeeded."
            );
            inbox.markProcessed("Failure webhook after success. Settlement request marked as adjustment required.");
            return;
        }

        if (settlementRequest.getStatus() == SettlementRequestStatus.RESERVED
                || settlementRequest.getStatus() == SettlementRequestStatus.SENT
                || settlementRequest.getStatus() == SettlementRequestStatus.UNKNOWN) {
            settlementRequest.markFailed(inbox.getProviderName(), inbox.getProviderReference());
            releaseAllocations(settlementRequest.getId());
            inbox.markProcessed("Settlement request marked as failed.");
            return;
        }

        inbox.markProcessed("Failure webhook ignored for current settlement request status.");
    }

    private void releaseAllocations(final Long settlementRequestId) {
        settlementAllocationRepository.findAllBySettlementRequestId(settlementRequestId)
                .forEach(SettlementAllocation::release);
    }

    private boolean isSuccessEvent(final String normalizedEventType) {
        return normalizedEventType.contains("SUCCESS") || normalizedEventType.contains("SUCCEEDED");
    }

    private boolean isFailureEvent(final String normalizedEventType) {
        return normalizedEventType.contains("FAIL") || normalizedEventType.contains("FAILED");
    }
}
