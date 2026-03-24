package com.hipster.settlement.service;

import com.hipster.global.exception.BadRequestException;
import com.hipster.global.exception.ConflictException;
import com.hipster.global.exception.ErrorCode;
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
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
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
class SettlementRequestServiceTest {

    @InjectMocks
    private SettlementRequestService settlementRequestService;

    @Mock
    private SettlementAvailableBalanceService settlementAvailableBalanceService;

    @Mock
    private SettlementRequestRepository settlementRequestRepository;

    @Mock
    private SettlementAllocationRepository settlementAllocationRepository;

    @Mock
    private SettlementPayoutOutboxRepository settlementPayoutOutboxRepository;

    @Mock
    private SettlementAdjustmentService settlementAdjustmentService;

    @Mock
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(settlementRequestService, "currency", "KRW");
        ReflectionTestUtils.setField(settlementRequestService, "minimumPayoutAmount", 100L);
        ReflectionTestUtils.setField(settlementRequestService, "providerName", "mock-payout");
    }

    @Test
    @DisplayName("정산 요청 생성은 사용자 락 이후 요청, 할당, outbox를 함께 만든다")
    void createRequestCreatesReservedRequestAllocationsAndOutbox() {
        final Long userId = 1L;
        final RewardLedgerEntry rewardLedgerEntry = accruedEntry(200L, userId, 700L);
        final SettlementAvailabilitySnapshot snapshot = new SettlementAvailabilitySnapshot(
                userId,
                "KRW",
                1_000L,
                700L,
                300L,
                100L,
                List.of(rewardLedgerEntry)
        );

        given(userRepository.findByIdForUpdate(userId)).willReturn(Optional.of(user(userId)));
        given(settlementRequestRepository.findFirstByUserIdAndStatusInOrderByRequestedAtDesc(eq(userId), any()))
                .willReturn(Optional.empty());
        given(settlementAvailableBalanceService.getAvailabilitySnapshot(userId)).willReturn(snapshot);
        given(settlementRequestRepository.saveAndFlush(any(SettlementRequest.class))).willAnswer(invocation -> {
            final SettlementRequest request = invocation.getArgument(0);
            ReflectionTestUtils.setField(request, "id", 55L);
            return request;
        });

        final SettlementRequestDetailResponse response = settlementRequestService.createRequest(
                userId,
                new CreateSettlementRequest("KRW", "bank-account-snapshot")
        );

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.status()).isEqualTo(SettlementRequestStatus.RESERVED);
        assertThat(response.currency()).isEqualTo("KRW");
        assertThat(response.requestedAmount()).isEqualTo(600L);
        assertThat(response.reservedAmount()).isEqualTo(700L);
        assertThat(response.destinationSnapshot()).isEqualTo("bank-account-snapshot");

        final ArgumentCaptor<List<SettlementAllocation>> allocationCaptor = ArgumentCaptor.forClass(List.class);
        verify(settlementAllocationRepository).saveAllAndFlush(allocationCaptor.capture());
        assertThat(allocationCaptor.getValue()).hasSize(1);
        assertThat(allocationCaptor.getValue().get(0).getRewardLedgerEntryId()).isEqualTo(rewardLedgerEntry.getId());
        assertThat(allocationCaptor.getValue().get(0).getAllocatedAmount()).isEqualTo(700L);

        final ArgumentCaptor<SettlementPayoutOutbox> outboxCaptor = ArgumentCaptor.forClass(SettlementPayoutOutbox.class);
        verify(settlementPayoutOutboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getProviderName()).isEqualTo("mock-payout");
        assertThat(outboxCaptor.getValue().getProviderIdempotencyKey()).isEqualTo(response.requestNo());
        verify(settlementAdjustmentService).resolveOpenDebitAdjustments(userId, 55L);
    }

    @Test
    @DisplayName("열린 정산 요청이 이미 있으면 새 요청 생성을 거절한다")
    void createRequestFailsWhenOpenRequestAlreadyExists() {
        final Long userId = 2L;
        final SettlementRequest existingRequest = SettlementRequest.requested(
                "STR-EXISTING-001",
                userId,
                "KRW",
                500L,
                500L,
                true,
                "bank-account-snapshot"
        );
        existingRequest.markReserved();

        given(userRepository.findByIdForUpdate(userId)).willReturn(Optional.of(user(userId)));
        given(settlementRequestRepository.findFirstByUserIdAndStatusInOrderByRequestedAtDesc(eq(userId), any()))
                .willReturn(Optional.of(existingRequest));

        assertThatThrownBy(() -> settlementRequestService.createRequest(
                userId,
                new CreateSettlementRequest("KRW", "bank-account-snapshot")
        ))
                .isInstanceOf(ConflictException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SETTLEMENT_REQUEST_ALREADY_OPEN);
    }

    @Test
    @DisplayName("정산 가능 금액이 최소 기준보다 작으면 요청 생성을 거절한다")
    void createRequestFailsWhenAvailableBalanceIsTooLow() {
        final Long userId = 3L;
        final RewardLedgerEntry rewardLedgerEntry = accruedEntry(300L, userId, 50L);
        final SettlementAvailabilitySnapshot snapshot = new SettlementAvailabilitySnapshot(
                userId,
                "KRW",
                50L,
                50L,
                0L,
                0L,
                List.of(rewardLedgerEntry)
        );

        given(userRepository.findByIdForUpdate(userId)).willReturn(Optional.of(user(userId)));
        given(settlementRequestRepository.findFirstByUserIdAndStatusInOrderByRequestedAtDesc(eq(userId), any()))
                .willReturn(Optional.empty());
        given(settlementAvailableBalanceService.getAvailabilitySnapshot(userId)).willReturn(snapshot);

        assertThatThrownBy(() -> settlementRequestService.createRequest(
                userId,
                new CreateSettlementRequest("KRW", "bank-account-snapshot")
        ))
                .isInstanceOf(BadRequestException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SETTLEMENT_AVAILABLE_BALANCE_TOO_LOW);
    }

    @Test
    @DisplayName("DB unique 제약으로 열린 요청 충돌이 나면 conflict로 번역한다")
    void createRequestTranslatesOpenRequestUniqueViolation() {
        final Long userId = 4L;
        final RewardLedgerEntry rewardLedgerEntry = accruedEntry(400L, userId, 700L);
        final SettlementAvailabilitySnapshot snapshot = new SettlementAvailabilitySnapshot(
                userId,
                "KRW",
                700L,
                700L,
                0L,
                0L,
                List.of(rewardLedgerEntry)
        );
        final SettlementRequest existingRequest = SettlementRequest.requested(
                "STR-EXISTING-DB-001",
                userId,
                "KRW",
                700L,
                700L,
                true,
                "bank-account-snapshot"
        );
        existingRequest.markReserved();

        given(userRepository.findByIdForUpdate(userId)).willReturn(Optional.of(user(userId)));
        given(settlementRequestRepository.findFirstByUserIdAndStatusInOrderByRequestedAtDesc(eq(userId), any()))
                .willReturn(Optional.empty(), Optional.of(existingRequest));
        given(settlementAvailableBalanceService.getAvailabilitySnapshot(userId)).willReturn(snapshot);
        given(settlementRequestRepository.saveAndFlush(any(SettlementRequest.class)))
                .willThrow(new DataIntegrityViolationException("duplicate open request"));

        assertThatThrownBy(() -> settlementRequestService.createRequest(
                userId,
                new CreateSettlementRequest("KRW", "bank-account-snapshot")
        ))
                .isInstanceOf(ConflictException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SETTLEMENT_REQUEST_ALREADY_OPEN);
    }

    @Test
    @DisplayName("DB unique 제약으로 allocation 충돌이 나면 일반 conflict로 번역한다")
    void createRequestTranslatesAllocationUniqueViolation() {
        final Long userId = 5L;
        final RewardLedgerEntry rewardLedgerEntry = accruedEntry(500L, userId, 700L);
        final SettlementAvailabilitySnapshot snapshot = new SettlementAvailabilitySnapshot(
                userId,
                "KRW",
                700L,
                700L,
                0L,
                0L,
                List.of(rewardLedgerEntry)
        );

        given(userRepository.findByIdForUpdate(userId)).willReturn(Optional.of(user(userId)));
        given(settlementRequestRepository.findFirstByUserIdAndStatusInOrderByRequestedAtDesc(eq(userId), any()))
                .willReturn(Optional.empty(), Optional.empty());
        given(settlementAvailableBalanceService.getAvailabilitySnapshot(userId)).willReturn(snapshot);
        given(settlementRequestRepository.saveAndFlush(any(SettlementRequest.class))).willAnswer(invocation -> {
            final SettlementRequest request = invocation.getArgument(0);
            ReflectionTestUtils.setField(request, "id", 77L);
            return request;
        });
        given(settlementAllocationRepository.saveAllAndFlush(any()))
                .willThrow(new DataIntegrityViolationException("duplicate active allocation"));

        assertThatThrownBy(() -> settlementRequestService.createRequest(
                userId,
                new CreateSettlementRequest("KRW", "bank-account-snapshot")
        ))
                .isInstanceOf(ConflictException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CONFLICT);
    }

    private RewardLedgerEntry accruedEntry(final Long id, final Long userId, final long pointsDelta) {
        final RewardLedgerEntry entry = RewardLedgerEntry.accrued(800L + id, userId, "catalog_bootstrap_v1", pointsDelta);
        ReflectionTestUtils.setField(entry, "id", id);
        ReflectionTestUtils.setField(entry, "createdAt", LocalDateTime.now().minusDays(10));
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
