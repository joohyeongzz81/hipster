package com.hipster.settlement.service;

import com.hipster.global.exception.BadRequestException;
import com.hipster.global.exception.ConflictException;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.NotFoundException;
import com.hipster.reward.domain.RewardLedgerEntry;
import com.hipster.settlement.domain.SettlementAllocation;
import com.hipster.settlement.domain.SettlementPayoutOutbox;
import com.hipster.settlement.domain.SettlementRequest;
import com.hipster.settlement.domain.SettlementRequestStatus;
import com.hipster.settlement.dto.request.CreateSettlementRequest;
import com.hipster.settlement.dto.response.SettlementRequestDetailResponse;
import com.hipster.settlement.repository.SettlementAllocationRepository;
import com.hipster.settlement.repository.SettlementPayoutOutboxRepository;
import com.hipster.settlement.repository.SettlementRequestRepository;
import com.hipster.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementRequestService {

    private static final List<SettlementRequestStatus> OPEN_REQUEST_STATUSES = List.of(
            SettlementRequestStatus.REQUESTED,
            SettlementRequestStatus.RESERVED,
            SettlementRequestStatus.SENT,
            SettlementRequestStatus.UNKNOWN
    );

    private final SettlementAvailableBalanceService settlementAvailableBalanceService;
    private final SettlementRequestRepository settlementRequestRepository;
    private final SettlementAllocationRepository settlementAllocationRepository;
    private final SettlementPayoutOutboxRepository settlementPayoutOutboxRepository;
    private final SettlementAdjustmentService settlementAdjustmentService;
    private final UserRepository userRepository;

    @Value("${hipster.settlement.currency:KRW}")
    private String currency;

    @Value("${hipster.settlement.minimum-payout-amount:100}")
    private long minimumPayoutAmount;

    @Value("${hipster.settlement.provider-name:mock-payout}")
    private String providerName;

    @Transactional
    public SettlementRequestDetailResponse createRequest(final Long userId,
                                                         final CreateSettlementRequest request) {
        validateCurrency(request.currency());
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));

        settlementRequestRepository.findFirstByUserIdAndStatusInOrderByRequestedAtDesc(userId, OPEN_REQUEST_STATUSES)
                .ifPresent(existing -> {
                    throw new ConflictException(ErrorCode.SETTLEMENT_REQUEST_ALREADY_OPEN);
                });

        final SettlementAvailabilitySnapshot snapshot = settlementAvailableBalanceService.getAvailabilitySnapshot(userId);
        if (!snapshot.payoutEligible(minimumPayoutAmount)) {
            throw new BadRequestException(ErrorCode.SETTLEMENT_AVAILABLE_BALANCE_TOO_LOW);
        }

        final SettlementRequest settlementRequest = SettlementRequest.requested(
                generateRequestNo(),
                userId,
                currency,
                snapshot.availableAmount(),
                snapshot.allocatableAmount(),
                true,
                request.destinationSnapshot()
        );
        settlementRequest.markReserved();

        final SettlementRequest savedRequest;
        try {
            savedRequest = settlementRequestRepository.saveAndFlush(settlementRequest);
            final List<SettlementAllocation> allocations = snapshot.allocatableEntries().stream()
                    .map(entry -> createAllocation(savedRequest, entry))
                    .toList();
            settlementAllocationRepository.saveAllAndFlush(allocations);
            settlementAdjustmentService.resolveOpenDebitAdjustments(userId, savedRequest.getId());
            settlementPayoutOutboxRepository.save(SettlementPayoutOutbox.pending(
                    savedRequest.getId(),
                    providerName,
                    savedRequest.getRequestNo()
            ));
        } catch (DataIntegrityViolationException exception) {
            translateReservationConflict(userId);
            throw exception;
        }

        return SettlementRequestDetailResponse.from(savedRequest);
    }

    @Transactional(readOnly = true)
    public SettlementRequestDetailResponse getRequest(final Long userId, final String requestNo) {
        final SettlementRequest settlementRequest = settlementRequestRepository.findByRequestNoAndUserId(requestNo, userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.SETTLEMENT_REQUEST_NOT_FOUND));
        return SettlementRequestDetailResponse.from(settlementRequest);
    }

    private void validateCurrency(final String requestCurrency) {
        if (requestCurrency == null || !currency.equalsIgnoreCase(requestCurrency.trim())) {
            throw new BadRequestException(ErrorCode.SETTLEMENT_UNSUPPORTED_CURRENCY);
        }
    }

    private String generateRequestNo() {
        return "STR-" + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 24)
                .toUpperCase(Locale.ROOT);
    }

    private SettlementAllocation createAllocation(final SettlementRequest savedRequest,
                                                  final RewardLedgerEntry rewardLedgerEntry) {
        return SettlementAllocation.reserve(
                savedRequest.getId(),
                rewardLedgerEntry.getId(),
                savedRequest.getUserId(),
                rewardLedgerEntry.getPointsDelta()
        );
    }

    private void translateReservationConflict(final Long userId) {
        if (settlementRequestRepository.findFirstByUserIdAndStatusInOrderByRequestedAtDesc(userId, OPEN_REQUEST_STATUSES).isPresent()) {
            throw new ConflictException(ErrorCode.SETTLEMENT_REQUEST_ALREADY_OPEN);
        }
        throw new ConflictException(ErrorCode.CONFLICT);
    }
}
