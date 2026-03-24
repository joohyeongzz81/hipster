package com.hipster.settlement.service;

import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.NotFoundException;
import com.hipster.reward.domain.RewardLedgerEntry;
import com.hipster.reward.repository.RewardLedgerEntryRepository;
import com.hipster.settlement.domain.SettlementAdjustmentStatus;
import com.hipster.settlement.domain.SettlementAdjustmentType;
import com.hipster.settlement.dto.response.SettlementAvailableBalanceResponse;
import com.hipster.settlement.repository.SettlementAdjustmentRepository;
import com.hipster.settlement.repository.SettlementAllocationRepository;
import com.hipster.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementAvailableBalanceService {

    private static final List<SettlementAdjustmentType> DEBIT_ADJUSTMENT_TYPES = List.of(
            SettlementAdjustmentType.DEBIT,
            SettlementAdjustmentType.CARRY_FORWARD_OFFSET
    );

    private final RewardLedgerEntryRepository rewardLedgerEntryRepository;
    private final SettlementAllocationRepository settlementAllocationRepository;
    private final SettlementAdjustmentRepository settlementAdjustmentRepository;
    private final UserRepository userRepository;

    @Value("${hipster.settlement.currency:KRW}")
    private String currency;

    @Value("${hipster.settlement.minimum-payout-amount:100}")
    private long minimumPayoutAmount;

    @Value("${hipster.settlement.hold-days:7}")
    private long holdDays;

    public SettlementAvailableBalanceResponse getAvailableBalance(final Long userId) {
        final SettlementAvailabilitySnapshot snapshot = getAvailabilitySnapshot(userId);
        return new SettlementAvailableBalanceResponse(
                snapshot.userId(),
                snapshot.currency(),
                snapshot.totalAccruedAmount(),
                snapshot.availableAmount(),
                snapshot.reservedAmount(),
                snapshot.payoutEligible(minimumPayoutAmount)
        );
    }

    SettlementAvailabilitySnapshot getAvailabilitySnapshot(final Long userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));

        final long totalAccruedAmount = normalizeAmount(rewardLedgerEntryRepository.sumPointsDeltaByUserId(userId));
        final long reservedAmount = normalizeAmount(settlementAllocationRepository.sumAllocatedAmountByUserIdAndActiveTrue(userId));
        final long openAdjustmentDebitAmount = normalizeAmount(
                settlementAdjustmentRepository.sumAmountDeltaByUserIdAndStatusAndAdjustmentTypeIn(
                        userId,
                        SettlementAdjustmentStatus.OPEN,
                        DEBIT_ADJUSTMENT_TYPES
                )
        );

        final List<RewardLedgerEntry> candidateEntries = rewardLedgerEntryRepository.findEligibleSettlementEntries(
                userId,
                LocalDateTime.now().minusDays(Math.max(holdDays, 0L))
        );
        final Set<Long> activeRewardEntryIds = settlementAllocationRepository.findActiveRewardLedgerEntryIdsByUserId(userId).stream()
                .collect(Collectors.toSet());

        final List<RewardLedgerEntry> allocatableEntries = candidateEntries.stream()
                .filter(entry -> !activeRewardEntryIds.contains(entry.getId()))
                .toList();

        final long allocatableAmount = allocatableEntries.stream()
                .mapToLong(RewardLedgerEntry::getPointsDelta)
                .sum();

        return new SettlementAvailabilitySnapshot(
                userId,
                currency,
                totalAccruedAmount,
                allocatableAmount,
                reservedAmount,
                openAdjustmentDebitAmount,
                allocatableEntries
        );
    }

    private long normalizeAmount(final Long amount) {
        return amount == null ? 0L : amount;
    }
}
