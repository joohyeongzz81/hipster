package com.hipster.reward.service;

import com.hipster.global.exception.BadRequestException;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.NotFoundException;
import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.domain.ModerationQueue;
import com.hipster.moderation.domain.ModerationStatus;
import com.hipster.moderation.repository.ModerationQueueRepository;
import com.hipster.reward.domain.RewardApprovalAccrualState;
import com.hipster.reward.domain.RewardCampaign;
import com.hipster.reward.domain.RewardCampaignParticipation;
import com.hipster.reward.domain.RewardLedgerEntry;
import com.hipster.reward.domain.RewardLedgerEntryStatus;
import com.hipster.reward.domain.RewardLedgerEntryType;
import com.hipster.reward.dto.response.RewardApprovalAccrualResponse;
import com.hipster.reward.dto.response.UserRewardApprovalAccrualListResponse;
import com.hipster.reward.dto.response.UserRewardBalanceResponse;
import com.hipster.reward.metrics.RewardMetricsRecorder;
import com.hipster.reward.repository.RewardCampaignParticipationRepository;
import com.hipster.reward.repository.RewardCampaignRepository;
import com.hipster.reward.repository.RewardLedgerEntryRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RewardLedgerServiceTest {

    private static final String DEFAULT_CAMPAIGN_CODE = "catalog_bootstrap_v1";
    private static final String DEFAULT_CAMPAIGN_NAME = "Catalog Bootstrap Default Campaign";
    private static final long POINTS_PER_APPROVAL = 100L;
    private static final long TOTAL_POINT_CAP = 1_000L;

    @InjectMocks
    private RewardLedgerService rewardLedgerService;

    @Mock
    private RewardCampaignRepository rewardCampaignRepository;

    @Mock
    private RewardCampaignParticipationRepository rewardCampaignParticipationRepository;

    @Mock
    private RewardLedgerEntryRepository rewardLedgerEntryRepository;

    @Mock
    private ModerationQueueRepository moderationQueueRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RewardMetricsRecorder rewardMetricsRecorder;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(rewardLedgerService, "defaultCampaignCode", DEFAULT_CAMPAIGN_CODE);
        ReflectionTestUtils.setField(rewardLedgerService, "defaultCampaignName", DEFAULT_CAMPAIGN_NAME);
        ReflectionTestUtils.setField(rewardLedgerService, "pointsPerApproval", POINTS_PER_APPROVAL);
        ReflectionTestUtils.setField(rewardLedgerService, "maxTotalPoints", TOTAL_POINT_CAP);
    }

    @Test
    @DisplayName("승인된 기여는 적립 항목으로 저장되고 참여 상태를 활성화한다")
    void accrueApprovedContribution_Success() {
        ModerationQueue approvedItem = approvedQueue(1L, 10L);
        RewardCampaign campaign = defaultCampaign(0L);

        given(rewardCampaignRepository.findByCodeForUpdate(DEFAULT_CAMPAIGN_CODE)).willReturn(Optional.of(campaign));
        given(rewardLedgerEntryRepository.findByApprovalIdAndCampaignCodeAndEntryType(
                approvedItem.getId(), DEFAULT_CAMPAIGN_CODE, RewardLedgerEntryType.ACCRUAL
        )).willReturn(Optional.empty());
        given(rewardLedgerEntryRepository.save(any(RewardLedgerEntry.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(rewardCampaignParticipationRepository.findByCampaignCodeAndUserId(
                DEFAULT_CAMPAIGN_CODE, approvedItem.getSubmitterId()
        )).willReturn(Optional.empty());
        given(rewardCampaignParticipationRepository.save(any(RewardCampaignParticipation.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        RewardLedgerEntry entry = rewardLedgerService.accrueApprovedContribution(approvedItem);

        assertThat(entry.getEntryType()).isEqualTo(RewardLedgerEntryType.ACCRUAL);
        assertThat(entry.getEntryStatus()).isEqualTo(RewardLedgerEntryStatus.ACCRUED);
        assertThat(entry.getPointsDelta()).isEqualTo(POINTS_PER_APPROVAL);
        assertThat(campaign.getGrantedPoints()).isEqualTo(POINTS_PER_APPROVAL);

        verify(rewardMetricsRecorder).recordApprovedInput();
        verify(rewardMetricsRecorder).recordDecision("accrued");
        verify(rewardMetricsRecorder).recordLedgerEntry(RewardLedgerEntryType.ACCRUAL.name());
        verify(rewardMetricsRecorder).recordOperationDuration(eq("get_or_create_default_campaign_for_update"), eq("success"), anyLong());
        verify(rewardMetricsRecorder).recordOperationDuration(eq("accrue_approved_contribution"), eq("success"), anyLong());
    }

    @Test
    @DisplayName("같은 승인 입력이 다시 들어오면 기존 적립 결과를 반환하고 중복 생성하지 않는다")
    void accrueApprovedContribution_DuplicateInput_ReturnsExistingEntry() {
        ModerationQueue approvedItem = approvedQueue(2L, 11L);
        RewardCampaign campaign = defaultCampaign(POINTS_PER_APPROVAL);
        RewardLedgerEntry existingEntry = RewardLedgerEntry.accrued(
                approvedItem.getId(), approvedItem.getSubmitterId(), DEFAULT_CAMPAIGN_CODE, POINTS_PER_APPROVAL
        );

        given(rewardCampaignRepository.findByCodeForUpdate(DEFAULT_CAMPAIGN_CODE)).willReturn(Optional.of(campaign));
        given(rewardLedgerEntryRepository.findByApprovalIdAndCampaignCodeAndEntryType(
                approvedItem.getId(), DEFAULT_CAMPAIGN_CODE, RewardLedgerEntryType.ACCRUAL
        )).willReturn(Optional.of(existingEntry));

        RewardLedgerEntry result = rewardLedgerService.accrueApprovedContribution(approvedItem);

        assertThat(result).isSameAs(existingEntry);
        assertThat(campaign.getGrantedPoints()).isEqualTo(POINTS_PER_APPROVAL);

        verify(rewardMetricsRecorder).recordApprovedInput();
        verify(rewardMetricsRecorder).recordDecision("duplicate_input_ignored");
        verify(rewardLedgerEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("같은 사용자의 다른 승인 기여는 같은 캠페인에서도 각각 적립된다")
    void accrueApprovedContribution_DifferentApprovalsForSameUser_AccruesEachContribution() {
        ModerationQueue firstApproval = approvedQueue(21L, 200L);
        ModerationQueue secondApproval = approvedQueue(22L, 200L);
        RewardCampaign campaign = defaultCampaign(0L);
        RewardCampaignParticipation existingParticipation = RewardCampaignParticipation.activeParticipation(
                DEFAULT_CAMPAIGN_CODE, firstApproval.getSubmitterId()
        );

        given(rewardCampaignRepository.findByCodeForUpdate(DEFAULT_CAMPAIGN_CODE)).willReturn(Optional.of(campaign));
        given(rewardLedgerEntryRepository.findByApprovalIdAndCampaignCodeAndEntryType(
                firstApproval.getId(), DEFAULT_CAMPAIGN_CODE, RewardLedgerEntryType.ACCRUAL
        )).willReturn(Optional.empty());
        given(rewardLedgerEntryRepository.findByApprovalIdAndCampaignCodeAndEntryType(
                secondApproval.getId(), DEFAULT_CAMPAIGN_CODE, RewardLedgerEntryType.ACCRUAL
        )).willReturn(Optional.empty());
        given(rewardLedgerEntryRepository.save(any(RewardLedgerEntry.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(rewardCampaignParticipationRepository.findByCampaignCodeAndUserId(
                DEFAULT_CAMPAIGN_CODE, firstApproval.getSubmitterId()
        )).willReturn(Optional.empty(), Optional.of(existingParticipation));
        given(rewardCampaignParticipationRepository.save(any(RewardCampaignParticipation.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        RewardLedgerEntry firstEntry = rewardLedgerService.accrueApprovedContribution(firstApproval);
        RewardLedgerEntry secondEntry = rewardLedgerService.accrueApprovedContribution(secondApproval);

        assertThat(firstEntry.getEntryStatus()).isEqualTo(RewardLedgerEntryStatus.ACCRUED);
        assertThat(secondEntry.getEntryStatus()).isEqualTo(RewardLedgerEntryStatus.ACCRUED);
        assertThat(firstEntry.getApprovalId()).isNotEqualTo(secondEntry.getApprovalId());
        assertThat(campaign.getGrantedPoints()).isEqualTo(POINTS_PER_APPROVAL * 2);

        verify(rewardMetricsRecorder, never()).recordDecision("participation_blocked");
    }

    @Test
    @DisplayName("캠페인 총 적립 한도를 넘기면 차단 항목만 남기고 포인트는 적립하지 않는다")
    void accrueApprovedContribution_CapExceeded_SavesBlockedEntry() {
        ModerationQueue approvedItem = approvedQueue(4L, 13L);
        RewardCampaign campaign = defaultCampaign(TOTAL_POINT_CAP);

        given(rewardCampaignRepository.findByCodeForUpdate(DEFAULT_CAMPAIGN_CODE)).willReturn(Optional.of(campaign));
        given(rewardLedgerEntryRepository.findByApprovalIdAndCampaignCodeAndEntryType(
                approvedItem.getId(), DEFAULT_CAMPAIGN_CODE, RewardLedgerEntryType.ACCRUAL
        )).willReturn(Optional.empty());
        given(rewardLedgerEntryRepository.save(any(RewardLedgerEntry.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        RewardLedgerEntry blockedEntry = rewardLedgerService.accrueApprovedContribution(approvedItem);

        assertThat(blockedEntry.getEntryStatus()).isEqualTo(RewardLedgerEntryStatus.CAP_EXCEEDED);
        assertThat(blockedEntry.getPointsDelta()).isZero();
        assertThat(campaign.getGrantedPoints()).isEqualTo(TOTAL_POINT_CAP);

        verify(rewardMetricsRecorder).recordDecision("cap_exceeded");
        verify(rewardMetricsRecorder).recordLedgerEntry(RewardLedgerEntryType.ACCRUAL.name());
    }

    @Test
    @DisplayName("취소 적립은 기존 적립을 상쇄하는 별도 항목으로 남고 참여 상태를 갱신한다")
    void reverseApprovalAccrual_Success() {
        Long approvalId = 5L;
        Long userId = 14L;
        RewardCampaign campaign = defaultCampaign(POINTS_PER_APPROVAL);
        RewardLedgerEntry accrualEntry = RewardLedgerEntry.accrued(approvalId, userId, DEFAULT_CAMPAIGN_CODE, POINTS_PER_APPROVAL);
        ReflectionTestUtils.setField(accrualEntry, "id", 100L);
        RewardLedgerEntry expectedReversalEntry = RewardLedgerEntry.reversal(
                approvalId, userId, DEFAULT_CAMPAIGN_CODE, POINTS_PER_APPROVAL, 100L, "manual reversal"
        );
        RewardCampaignParticipation participation = RewardCampaignParticipation.activeParticipation(DEFAULT_CAMPAIGN_CODE, userId);

        given(rewardLedgerEntryRepository.findByApprovalIdAndCampaignCodeAndEntryType(
                approvalId, DEFAULT_CAMPAIGN_CODE, RewardLedgerEntryType.ACCRUAL
        )).willReturn(Optional.of(accrualEntry));
        given(rewardLedgerEntryRepository.findByApprovalIdAndCampaignCodeAndEntryType(
                approvalId, DEFAULT_CAMPAIGN_CODE, RewardLedgerEntryType.REVERSAL
        )).willReturn(Optional.empty());
        given(rewardCampaignRepository.findByCodeForUpdate(DEFAULT_CAMPAIGN_CODE)).willReturn(Optional.of(campaign));
        given(rewardLedgerEntryRepository.sumPointsDeltaByUserIdAndCampaignCode(userId, DEFAULT_CAMPAIGN_CODE))
                .willReturn(POINTS_PER_APPROVAL);
        given(rewardLedgerEntryRepository.save(any(RewardLedgerEntry.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(rewardCampaignParticipationRepository.findByCampaignCodeAndUserId(DEFAULT_CAMPAIGN_CODE, userId))
                .willReturn(Optional.of(participation));
        ModerationQueue approvedQueue = approvedQueue(approvalId, userId);
        given(moderationQueueRepository.findById(approvalId)).willReturn(Optional.of(approvedQueue));
        given(rewardLedgerEntryRepository.findAllByApprovalIdOrderByCreatedAtAsc(approvalId))
                .willReturn(List.of(accrualEntry, expectedReversalEntry));

        RewardApprovalAccrualResponse response = rewardLedgerService.reverseApprovalAccrual(approvalId, "manual reversal");

        assertThat(response.accrualState()).isEqualTo(RewardApprovalAccrualState.REVERSED);
        assertThat(response.netPoints()).isZero();
        assertThat(campaign.getGrantedPoints()).isZero();
        assertThat(participation.isActive()).isFalse();

        ArgumentCaptor<RewardLedgerEntry> entryCaptor = ArgumentCaptor.forClass(RewardLedgerEntry.class);
        verify(rewardLedgerEntryRepository).save(entryCaptor.capture());
        RewardLedgerEntry reversalEntry = entryCaptor.getValue();
        assertThat(reversalEntry.getEntryType()).isEqualTo(RewardLedgerEntryType.REVERSAL);
        assertThat(reversalEntry.getEntryStatus()).isEqualTo(RewardLedgerEntryStatus.REVERSED);
        assertThat(reversalEntry.getPointsDelta()).isEqualTo(-POINTS_PER_APPROVAL);
        assertThat(reversalEntry.getReferenceEntryId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("다른 적립이 남아 있으면 reversal 이후에도 참여 상태는 활성으로 유지된다")
    void reverseApprovalAccrual_WithRemainingPoints_KeepsParticipationActive() {
        Long approvalId = 31L;
        Long userId = 300L;
        RewardCampaign campaign = defaultCampaign(POINTS_PER_APPROVAL * 2);
        RewardLedgerEntry accrualEntry = RewardLedgerEntry.accrued(approvalId, userId, DEFAULT_CAMPAIGN_CODE, POINTS_PER_APPROVAL);
        ReflectionTestUtils.setField(accrualEntry, "id", 3000L);
        RewardCampaignParticipation participation = RewardCampaignParticipation.activeParticipation(DEFAULT_CAMPAIGN_CODE, userId);
        ModerationQueue approvedQueue = approvedQueue(approvalId, userId);

        given(rewardLedgerEntryRepository.findByApprovalIdAndCampaignCodeAndEntryType(
                approvalId, DEFAULT_CAMPAIGN_CODE, RewardLedgerEntryType.ACCRUAL
        )).willReturn(Optional.of(accrualEntry));
        given(rewardLedgerEntryRepository.findByApprovalIdAndCampaignCodeAndEntryType(
                approvalId, DEFAULT_CAMPAIGN_CODE, RewardLedgerEntryType.REVERSAL
        )).willReturn(Optional.empty());
        given(rewardCampaignRepository.findByCodeForUpdate(DEFAULT_CAMPAIGN_CODE)).willReturn(Optional.of(campaign));
        given(rewardLedgerEntryRepository.sumPointsDeltaByUserIdAndCampaignCode(userId, DEFAULT_CAMPAIGN_CODE))
                .willReturn(POINTS_PER_APPROVAL * 2);
        given(rewardLedgerEntryRepository.save(any(RewardLedgerEntry.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(rewardCampaignParticipationRepository.findByCampaignCodeAndUserId(DEFAULT_CAMPAIGN_CODE, userId))
                .willReturn(Optional.of(participation));
        given(moderationQueueRepository.findById(approvalId)).willReturn(Optional.of(approvedQueue));
        given(rewardLedgerEntryRepository.findAllByApprovalIdOrderByCreatedAtAsc(approvalId))
                .willReturn(List.of(
                        accrualEntry,
                        RewardLedgerEntry.reversal(approvalId, userId, DEFAULT_CAMPAIGN_CODE, POINTS_PER_APPROVAL, 3000L, "partial reversal")
                ));

        rewardLedgerService.reverseApprovalAccrual(approvalId, "partial reversal");

        assertThat(participation.isActive()).isTrue();
    }

    @Test
    @DisplayName("승인된 기여에 적립 기록이 없으면 누락 상태로 조회된다")
    void getApprovalAccrual_MissingAccrual_ReturnsMissing() {
        Long approvalId = 6L;
        ModerationQueue approvedQueue = approvedQueue(approvalId, 15L);

        given(moderationQueueRepository.findById(approvalId)).willReturn(Optional.of(approvedQueue));
        given(rewardLedgerEntryRepository.findAllByApprovalIdOrderByCreatedAtAsc(approvalId)).willReturn(List.of());

        RewardApprovalAccrualResponse response = rewardLedgerService.getApprovalAccrual(approvalId);

        assertThat(response.accrualState()).isEqualTo(RewardApprovalAccrualState.MISSING);
        assertThat(response.netPoints()).isZero();
        assertThat(response.entries()).isEmpty();
    }

    @Test
    @DisplayName("사용자 잔액은 원장 합계 기준으로 계산된다")
    void getUserRewardBalance_SumsLedgerEntries() {
        Long userId = 16L;
        User user = User.builder()
                .username("reward-user")
                .email("reward-user@example.com")
                .passwordHash("hashed-password")
                .build();
        ReflectionTestUtils.setField(user, "id", userId);

        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(rewardLedgerEntryRepository.sumPointsDeltaByUserId(userId)).willReturn(70L);
        given(rewardLedgerEntryRepository.sumPointsDeltaByUserIdAndCampaignCode(userId, DEFAULT_CAMPAIGN_CODE))
                .willReturn(70L);

        UserRewardBalanceResponse response = rewardLedgerService.getUserRewardBalance(userId);

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.totalPoints()).isEqualTo(70L);
        assertThat(response.activeParticipation()).isTrue();
    }

    @Test
    @DisplayName("사용자는 자신의 승인 기준 적립 상태를 목록으로 조회할 수 있다")
    void getUserApprovalAccruals_ReturnsApprovalScopedAccruals() {
        Long userId = 400L;
        User user = User.builder()
                .username("reward-user-2")
                .email("reward-user-2@example.com")
                .passwordHash("hashed-password")
                .build();
        ReflectionTestUtils.setField(user, "id", userId);

        ModerationQueue firstApproval = approvedQueue(41L, userId);
        ModerationQueue secondApproval = approvedQueue(42L, userId);
        RewardLedgerEntry firstEntry = RewardLedgerEntry.accrued(41L, userId, DEFAULT_CAMPAIGN_CODE, POINTS_PER_APPROVAL);
        RewardLedgerEntry secondEntry = RewardLedgerEntry.blocked(
                42L, userId, DEFAULT_CAMPAIGN_CODE, RewardLedgerEntryStatus.CAP_EXCEEDED, "CAMPAIGN_POINT_CAP_EXCEEDED"
        );

        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(moderationQueueRepository.findBySubmitterIdAndStatusInOrderBySubmittedAtDesc(
                userId, List.of(ModerationStatus.APPROVED)
        )).willReturn(List.of(firstApproval, secondApproval));
        given(rewardLedgerEntryRepository.findAllByApprovalIdInOrderByCreatedAtAsc(List.of(41L, 42L)))
                .willReturn(List.of(firstEntry, secondEntry));

        UserRewardApprovalAccrualListResponse response = rewardLedgerService.getUserApprovalAccruals(userId);

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.totalApprovals()).isEqualTo(2);
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).approvalId()).isEqualTo(41L);
        assertThat(response.items().get(0).entityType()).isEqualTo(EntityType.ARTIST);
        assertThat(response.items().get(0).accrualState()).isEqualTo(RewardApprovalAccrualState.ACCRUED);
        assertThat(response.items().get(1).approvalId()).isEqualTo(42L);
        assertThat(response.items().get(1).accrualState()).isEqualTo(RewardApprovalAccrualState.CAP_EXCEEDED);
        verify(rewardMetricsRecorder).recordOperationDuration(eq("get_user_approval_accruals"), eq("success"), anyLong());
    }

    @Test
    @DisplayName("승인 상태가 아닌 항목은 적립 입력으로 사용할 수 없다")
    void accrueApprovedContribution_NotApproved_ThrowsBadRequest() {
        ModerationQueue pendingItem = ModerationQueue.builder()
                .entityType(EntityType.ARTIST)
                .entityId(100L)
                .submitterId(17L)
                .metaComment("pending")
                .priority(2)
                .build();
        ReflectionTestUtils.setField(pendingItem, "id", 7L);

        assertThatThrownBy(() -> rewardLedgerService.accrueApprovedContribution(pendingItem))
                .isInstanceOfSatisfying(BadRequestException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REWARD_APPROVAL_NOT_ELIGIBLE));
    }

    @Test
    @DisplayName("적립되지 않은 승인 건은 reversal을 만들 수 없다")
    void reverseApprovalAccrual_WithoutAccrual_ThrowsNotFound() {
        Long approvalId = 8L;

        given(rewardLedgerEntryRepository.findByApprovalIdAndCampaignCodeAndEntryType(
                approvalId, DEFAULT_CAMPAIGN_CODE, RewardLedgerEntryType.ACCRUAL
        )).willReturn(Optional.empty());

        assertThatThrownBy(() -> rewardLedgerService.reverseApprovalAccrual(approvalId, "missing"))
                .isInstanceOfSatisfying(NotFoundException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.REWARD_ACCRUAL_NOT_FOUND));
    }

    private ModerationQueue approvedQueue(final Long approvalId, final Long submitterId) {
        ModerationQueue queue = ModerationQueue.builder()
                .entityType(EntityType.ARTIST)
                .entityId(200L + approvalId)
                .submitterId(submitterId)
                .metaComment("approved contribution")
                .priority(2)
                .build();
        ReflectionTestUtils.setField(queue, "id", approvalId);
        queue.approve("approved");
        return queue;
    }

    private RewardCampaign defaultCampaign(final long grantedPoints) {
        RewardCampaign campaign = RewardCampaign.defaultCampaign(
                DEFAULT_CAMPAIGN_CODE,
                DEFAULT_CAMPAIGN_NAME,
                POINTS_PER_APPROVAL,
                TOTAL_POINT_CAP
        );
        ReflectionTestUtils.setField(campaign, "grantedPoints", grantedPoints);
        return campaign;
    }
}
