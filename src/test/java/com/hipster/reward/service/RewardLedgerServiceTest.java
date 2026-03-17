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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RewardLedgerServiceTest {

    private static final String DEFAULT_CAMPAIGN_CODE = "catalog_bootstrap_v1";
    private static final String DEFAULT_CAMPAIGN_NAME = "승인 기여 기본 적립 캠페인";
    private static final long POINTS_PER_APPROVAL = 100L;
    private static final long TOTAL_POINT_CAP = 1000L;

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
    @DisplayName("승인된 기여는 적립 원장에 한 번 적립되고 참여 상태가 활성화된다")
    void accrueApprovedContribution_Success() {
        ModerationQueue approvedItem = approvedQueue(1L, 10L);
        RewardCampaign campaign = defaultCampaign(0L);

        given(rewardCampaignRepository.findByCodeForUpdate(DEFAULT_CAMPAIGN_CODE)).willReturn(Optional.of(campaign));
        given(rewardLedgerEntryRepository.findByApprovalIdAndCampaignCodeAndEntryType(
                approvedItem.getId(), DEFAULT_CAMPAIGN_CODE, RewardLedgerEntryType.ACCRUAL
        )).willReturn(Optional.empty());
        given(rewardCampaignParticipationRepository.existsByCampaignCodeAndUserIdAndActiveTrue(
                DEFAULT_CAMPAIGN_CODE, approvedItem.getSubmitterId()
        )).willReturn(false);
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
    }

    @Test
    @DisplayName("같은 승인 입력이 다시 들어오면 기존 적립을 반환하고 중복 생성하지 않는다")
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
    @DisplayName("같은 사용자-같은 캠페인 활성 참여가 있으면 중복 지급 대신 차단 기록을 남긴다")
    void accrueApprovedContribution_ParticipationBlocked_SavesBlockedEntry() {
        ModerationQueue approvedItem = approvedQueue(3L, 12L);
        RewardCampaign campaign = defaultCampaign(POINTS_PER_APPROVAL);

        given(rewardCampaignRepository.findByCodeForUpdate(DEFAULT_CAMPAIGN_CODE)).willReturn(Optional.of(campaign));
        given(rewardLedgerEntryRepository.findByApprovalIdAndCampaignCodeAndEntryType(
                approvedItem.getId(), DEFAULT_CAMPAIGN_CODE, RewardLedgerEntryType.ACCRUAL
        )).willReturn(Optional.empty());
        given(rewardCampaignParticipationRepository.existsByCampaignCodeAndUserIdAndActiveTrue(
                DEFAULT_CAMPAIGN_CODE, approvedItem.getSubmitterId()
        )).willReturn(true);
        given(rewardLedgerEntryRepository.save(any(RewardLedgerEntry.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        RewardLedgerEntry blockedEntry = rewardLedgerService.accrueApprovedContribution(approvedItem);

        assertThat(blockedEntry.getEntryStatus()).isEqualTo(RewardLedgerEntryStatus.PARTICIPATION_BLOCKED);
        assertThat(blockedEntry.getPointsDelta()).isZero();
        assertThat(campaign.getGrantedPoints()).isEqualTo(POINTS_PER_APPROVAL);

        verify(rewardMetricsRecorder).recordDecision("participation_blocked");
        verify(rewardMetricsRecorder).recordLedgerEntry(RewardLedgerEntryType.ACCRUAL.name());
    }

    @Test
    @DisplayName("캠페인 총 적립 상한을 넘기면 차단 기록만 남기고 포인트를 적립하지 않는다")
    void accrueApprovedContribution_CapExceeded_SavesBlockedEntry() {
        ModerationQueue approvedItem = approvedQueue(4L, 13L);
        RewardCampaign campaign = defaultCampaign(TOTAL_POINT_CAP);

        given(rewardCampaignRepository.findByCodeForUpdate(DEFAULT_CAMPAIGN_CODE)).willReturn(Optional.of(campaign));
        given(rewardLedgerEntryRepository.findByApprovalIdAndCampaignCodeAndEntryType(
                approvedItem.getId(), DEFAULT_CAMPAIGN_CODE, RewardLedgerEntryType.ACCRUAL
        )).willReturn(Optional.empty());
        given(rewardCampaignParticipationRepository.existsByCampaignCodeAndUserIdAndActiveTrue(
                DEFAULT_CAMPAIGN_CODE, approvedItem.getSubmitterId()
        )).willReturn(false);
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
    @DisplayName("취소 적립은 기존 적립을 상쇄하는 음수 원장 항목으로 남기고 참여 상태를 비활성화한다")
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
    @DisplayName("승인된 기여에 적립 기록이 없으면 누락 상태로 판별된다")
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
    @DisplayName("사용자 잔액은 원장 항목 합계의 결과값으로 조회된다")
    void getUserRewardBalance_SumsLedgerEntries() {
        Long userId = 16L;
        User user = User.builder()
                .username("reward-user")
                .email("reward-user@example.com")
                .passwordHash("hashed-password")
                .build();
        ReflectionTestUtils.setField(user, "id", userId);

        RewardCampaignParticipation participation = RewardCampaignParticipation.activeParticipation(DEFAULT_CAMPAIGN_CODE, userId);

        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(rewardLedgerEntryRepository.sumPointsDeltaByUserId(userId)).willReturn(70L);
        given(rewardCampaignParticipationRepository.findByCampaignCodeAndUserId(DEFAULT_CAMPAIGN_CODE, userId))
                .willReturn(Optional.of(participation));

        UserRewardBalanceResponse response = rewardLedgerService.getUserRewardBalance(userId);

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.totalPoints()).isEqualTo(70L);
        assertThat(response.activeParticipation()).isTrue();
    }

    @Test
    @DisplayName("승인 상태가 아닌 항목은 적립 입력으로 허용되지 않는다")
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
    @DisplayName("적립되지 않은 승인 건은 취소 적립을 만들 수 없다")
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
                .entityId(200L)
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
