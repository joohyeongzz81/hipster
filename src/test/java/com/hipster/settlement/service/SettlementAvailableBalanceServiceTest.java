package com.hipster.settlement.service;

import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.NotFoundException;
import com.hipster.reward.domain.RewardLedgerEntry;
import com.hipster.settlement.domain.SettlementAdjustmentStatus;
import com.hipster.settlement.dto.response.SettlementAvailableBalanceResponse;
import com.hipster.settlement.repository.SettlementAdjustmentRepository;
import com.hipster.settlement.repository.SettlementAllocationRepository;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import com.hipster.reward.repository.RewardLedgerEntryRepository;
import org.junit.jupiter.api.BeforeEach;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SettlementAvailableBalanceServiceTest {

    @InjectMocks
    private SettlementAvailableBalanceService settlementAvailableBalanceService;

    @Mock
    private RewardLedgerEntryRepository rewardLedgerEntryRepository;

    @Mock
    private SettlementAllocationRepository settlementAllocationRepository;

    @Mock
    private SettlementAdjustmentRepository settlementAdjustmentRepository;

    @Mock
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(settlementAvailableBalanceService, "currency", "KRW");
        ReflectionTestUtils.setField(settlementAvailableBalanceService, "minimumPayoutAmount", 100L);
        ReflectionTestUtils.setField(settlementAvailableBalanceService, "holdDays", 7L);
    }

    @Test
    @DisplayName("정산 가능 금액은 활성 예약과 미해결 차감 조정을 제외하고 계산한다")
    void availableBalanceExcludesReservedAllocationsAndOpenDebitAdjustments() {
        final Long userId = 1L;
        final RewardLedgerEntry firstEntry = accruedEntry(101L, userId, 700L, LocalDateTime.now().minusDays(10));
        final RewardLedgerEntry secondEntry = accruedEntry(102L, userId, 300L, LocalDateTime.now().minusDays(10));

        given(userRepository.findById(userId)).willReturn(Optional.of(user(userId)));
        given(rewardLedgerEntryRepository.sumPointsDeltaByUserId(userId)).willReturn(1_000L);
        given(rewardLedgerEntryRepository.findEligibleSettlementEntries(eq(userId), any(LocalDateTime.class)))
                .willReturn(List.of(firstEntry, secondEntry));
        given(settlementAllocationRepository.findActiveRewardLedgerEntryIdsByUserId(userId))
                .willReturn(List.of(secondEntry.getId()));
        given(settlementAllocationRepository.sumAllocatedAmountByUserIdAndActiveTrue(userId))
                .willReturn(300L);
        given(settlementAdjustmentRepository.sumAmountDeltaByUserIdAndStatusAndAdjustmentTypeIn(
                eq(userId),
                eq(SettlementAdjustmentStatus.OPEN),
                any()
        )).willReturn(100L);

        final SettlementAvailableBalanceResponse response = settlementAvailableBalanceService.getAvailableBalance(userId);

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.currency()).isEqualTo("KRW");
        assertThat(response.totalAccruedAmount()).isEqualTo(1_000L);
        assertThat(response.availableAmount()).isEqualTo(600L);
        assertThat(response.reservedAmount()).isEqualTo(300L);
        assertThat(response.payoutEligible()).isTrue();

        final ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(rewardLedgerEntryRepository).findEligibleSettlementEntries(eq(userId), cutoffCaptor.capture());
        assertThat(cutoffCaptor.getValue()).isBeforeOrEqualTo(LocalDateTime.now().minusDays(6));
    }

    @Test
    @DisplayName("존재하지 않는 사용자의 정산 가능 금액 조회는 실패한다")
    void availableBalanceFailsWhenUserDoesNotExist() {
        final Long userId = 99L;
        given(userRepository.findById(userId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> settlementAvailableBalanceService.getAvailableBalance(userId))
                .isInstanceOf(NotFoundException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    private RewardLedgerEntry accruedEntry(final Long id,
                                           final Long userId,
                                           final long pointsDelta,
                                           final LocalDateTime createdAt) {
        final RewardLedgerEntry entry = RewardLedgerEntry.accrued(500L + id, userId, "catalog_bootstrap_v1", pointsDelta);
        ReflectionTestUtils.setField(entry, "id", id);
        ReflectionTestUtils.setField(entry, "createdAt", createdAt);
        return entry;
    }

    private User user(final Long userId) {
        final User user = User.builder()
                .username("settlement_user_" + userId)
                .email("settlement" + userId + "@test.com")
                .passwordHash("hash")
                .build();
        ReflectionTestUtils.setField(user, "id", userId);
        return user;
    }
}
