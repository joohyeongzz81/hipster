package com.hipster.reward.service;

import com.hipster.global.exception.BadRequestException;
import com.hipster.global.exception.ConflictException;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.NotFoundException;
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
import com.hipster.reward.dto.response.RewardLedgerEntryResponse;
import com.hipster.reward.dto.response.UserRewardBalanceResponse;
import com.hipster.reward.metrics.RewardMetricsRecorder;
import com.hipster.reward.repository.RewardCampaignParticipationRepository;
import com.hipster.reward.repository.RewardCampaignRepository;
import com.hipster.reward.repository.RewardLedgerEntryRepository;
import com.hipster.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RewardLedgerService {

    private static final String CAP_EXCEEDED_REASON = "CAMPAIGN_POINT_CAP_EXCEEDED";
    private static final String PARTICIPATION_BLOCKED_REASON = "PARTICIPATION_RULE_BLOCKED";

    @Value("${hipster.reward.default-campaign-code:catalog_bootstrap_v1}")
    private String defaultCampaignCode;

    @Value("${hipster.reward.default-campaign-name:승인 기여 기본 적립 캠페인}")
    private String defaultCampaignName;

    @Value("${hipster.reward.points-per-approval:100}")
    private long pointsPerApproval;

    @Value("${hipster.reward.max-total-points:100000}")
    private long maxTotalPoints;

    private final RewardCampaignRepository rewardCampaignRepository;
    private final RewardCampaignParticipationRepository rewardCampaignParticipationRepository;
    private final RewardLedgerEntryRepository rewardLedgerEntryRepository;
    private final ModerationQueueRepository moderationQueueRepository;
    private final UserRepository userRepository;
    private final RewardMetricsRecorder rewardMetricsRecorder;

    @Transactional
    public RewardLedgerEntry accrueApprovedContribution(final ModerationQueue approvedItem) {
        validateApprovalInput(approvedItem);
        rewardMetricsRecorder.recordApprovedInput();

        final RewardCampaign campaign = getOrCreateDefaultCampaignForUpdate();
        final RewardLedgerEntry existingEntry = rewardLedgerEntryRepository
                .findByApprovalIdAndCampaignCodeAndEntryType(approvedItem.getId(), campaign.getCode(), RewardLedgerEntryType.ACCRUAL)
                .orElse(null);

        if (existingEntry != null) {
            rewardMetricsRecorder.recordDecision("duplicate_input_ignored");
            return existingEntry;
        }

        if (rewardCampaignParticipationRepository.existsByCampaignCodeAndUserIdAndActiveTrue(campaign.getCode(), approvedItem.getSubmitterId())) {
            final RewardLedgerEntry blockedEntry = rewardLedgerEntryRepository.save(
                    RewardLedgerEntry.blocked(
                            approvedItem.getId(),
                            approvedItem.getSubmitterId(),
                            campaign.getCode(),
                            RewardLedgerEntryStatus.PARTICIPATION_BLOCKED,
                            PARTICIPATION_BLOCKED_REASON
                    )
            );
            rewardMetricsRecorder.recordDecision("participation_blocked");
            rewardMetricsRecorder.recordLedgerEntry(RewardLedgerEntryType.ACCRUAL.name());
            return blockedEntry;
        }

        if (!campaign.canAccrue(campaign.getPointsPerApproval())) {
            final RewardLedgerEntry blockedEntry = rewardLedgerEntryRepository.save(
                    RewardLedgerEntry.blocked(
                            approvedItem.getId(),
                            approvedItem.getSubmitterId(),
                            campaign.getCode(),
                            RewardLedgerEntryStatus.CAP_EXCEEDED,
                            CAP_EXCEEDED_REASON
                    )
            );
            rewardMetricsRecorder.recordDecision("cap_exceeded");
            rewardMetricsRecorder.recordLedgerEntry(RewardLedgerEntryType.ACCRUAL.name());
            return blockedEntry;
        }

        campaign.accrue(campaign.getPointsPerApproval());
        final RewardLedgerEntry accruedEntry = rewardLedgerEntryRepository.save(
                RewardLedgerEntry.accrued(
                        approvedItem.getId(),
                        approvedItem.getSubmitterId(),
                        campaign.getCode(),
                        campaign.getPointsPerApproval()
                )
        );

        final RewardCampaignParticipation participation = rewardCampaignParticipationRepository
                .findByCampaignCodeAndUserId(campaign.getCode(), approvedItem.getSubmitterId())
                .orElseGet(() -> RewardCampaignParticipation.activeParticipation(campaign.getCode(), approvedItem.getSubmitterId()));
        participation.activate();
        rewardCampaignParticipationRepository.save(participation);

        rewardMetricsRecorder.recordDecision("accrued");
        rewardMetricsRecorder.recordLedgerEntry(RewardLedgerEntryType.ACCRUAL.name());
        return accruedEntry;
    }

    @Transactional(readOnly = true)
    public RewardApprovalAccrualResponse getApprovalAccrual(final Long approvalId) {
        final ModerationQueue queueItem = moderationQueueRepository.findById(approvalId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.MODERATION_ITEM_NOT_FOUND));
        final List<RewardLedgerEntry> entries = rewardLedgerEntryRepository.findAllByApprovalIdOrderByCreatedAtAsc(approvalId);
        final RewardApprovalAccrualState accrualState = deriveAccrualState(queueItem.getStatus(), entries);
        final long netPoints = entries.stream().mapToLong(RewardLedgerEntry::getPointsDelta).sum();

        return new RewardApprovalAccrualResponse(
                approvalId,
                queueItem.getStatus(),
                queueItem.getSubmitterId(),
                defaultCampaignCode,
                accrualState,
                netPoints,
                entries.stream().map(RewardLedgerEntryResponse::from).toList()
        );
    }

    @Transactional
    public RewardApprovalAccrualResponse reverseApprovalAccrual(final Long approvalId, final String reason) {
        final RewardLedgerEntry accrualEntry = rewardLedgerEntryRepository
                .findByApprovalIdAndCampaignCodeAndEntryType(approvalId, defaultCampaignCode, RewardLedgerEntryType.ACCRUAL)
                .orElseThrow(() -> new NotFoundException(ErrorCode.REWARD_ACCRUAL_NOT_FOUND));

        if (accrualEntry.getEntryStatus() != RewardLedgerEntryStatus.ACCRUED) {
            throw new BadRequestException(ErrorCode.REWARD_REVERSAL_NOT_ALLOWED);
        }

        final RewardLedgerEntry existingReversal = rewardLedgerEntryRepository
                .findByApprovalIdAndCampaignCodeAndEntryType(approvalId, defaultCampaignCode, RewardLedgerEntryType.REVERSAL)
                .orElse(null);
        if (existingReversal != null) {
            rewardMetricsRecorder.recordDecision("duplicate_reversal_ignored");
            return getApprovalAccrual(approvalId);
        }

        final RewardCampaign campaign = rewardCampaignRepository.findByCodeForUpdate(defaultCampaignCode)
                .orElseThrow(() -> new NotFoundException(ErrorCode.REWARD_CAMPAIGN_NOT_FOUND));
        campaign.reverse(accrualEntry.getPointsDelta());

        final RewardLedgerEntry reversalEntry = rewardLedgerEntryRepository.save(
                RewardLedgerEntry.reversal(
                        approvalId,
                        accrualEntry.getUserId(),
                        accrualEntry.getCampaignCode(),
                        accrualEntry.getPointsDelta(),
                        accrualEntry.getId(),
                        reason
                )
        );

        rewardCampaignParticipationRepository.findByCampaignCodeAndUserId(accrualEntry.getCampaignCode(), accrualEntry.getUserId())
                .ifPresent(participation -> {
                    participation.deactivate();
                    rewardCampaignParticipationRepository.save(participation);
                });

        rewardMetricsRecorder.recordDecision("reversed");
        rewardMetricsRecorder.recordLedgerEntry(reversalEntry.getEntryType().name());
        return getApprovalAccrual(approvalId);
    }

    @Transactional(readOnly = true)
    public UserRewardBalanceResponse getUserRewardBalance(final Long userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));

        final long totalPoints = rewardLedgerEntryRepository.sumPointsDeltaByUserId(userId);
        final boolean activeParticipation = rewardCampaignParticipationRepository
                .findByCampaignCodeAndUserId(defaultCampaignCode, userId)
                .map(RewardCampaignParticipation::isActive)
                .orElse(false);

        return new UserRewardBalanceResponse(userId, defaultCampaignCode, totalPoints, activeParticipation);
    }

    private void validateApprovalInput(final ModerationQueue approvedItem) {
        if (approvedItem.getId() == null || approvedItem.getSubmitterId() == null) {
            throw new BadRequestException(ErrorCode.REWARD_APPROVAL_NOT_ELIGIBLE);
        }
        if (approvedItem.getStatus() != ModerationStatus.APPROVED) {
            throw new BadRequestException(ErrorCode.REWARD_APPROVAL_NOT_ELIGIBLE);
        }
    }

    private RewardCampaign getOrCreateDefaultCampaignForUpdate() {
        final RewardCampaign existingCampaign = rewardCampaignRepository.findByCodeForUpdate(defaultCampaignCode).orElse(null);
        if (existingCampaign != null) {
            return existingCampaign;
        }

        try {
            return rewardCampaignRepository.save(RewardCampaign.defaultCampaign(
                    defaultCampaignCode,
                    defaultCampaignName,
                    pointsPerApproval,
                    maxTotalPoints
            ));
        } catch (DataIntegrityViolationException exception) {
            return rewardCampaignRepository.findByCodeForUpdate(defaultCampaignCode)
                    .orElseThrow(() -> new ConflictException(ErrorCode.CONFLICT));
        }
    }

    private RewardApprovalAccrualState deriveAccrualState(final ModerationStatus moderationStatus,
                                                          final List<RewardLedgerEntry> entries) {
        if (entries.stream().anyMatch(entry -> entry.getEntryType() == RewardLedgerEntryType.REVERSAL)) {
            return RewardApprovalAccrualState.REVERSED;
        }

        final RewardLedgerEntry accrualEntry = entries.stream()
                .filter(entry -> entry.getEntryType() == RewardLedgerEntryType.ACCRUAL)
                .findFirst()
                .orElse(null);

        if (accrualEntry == null) {
            return moderationStatus == ModerationStatus.APPROVED
                    ? RewardApprovalAccrualState.MISSING
                    : RewardApprovalAccrualState.NOT_ELIGIBLE;
        }

        return switch (accrualEntry.getEntryStatus()) {
            case ACCRUED -> RewardApprovalAccrualState.ACCRUED;
            case PARTICIPATION_BLOCKED -> RewardApprovalAccrualState.PARTICIPATION_BLOCKED;
            case CAP_EXCEEDED -> RewardApprovalAccrualState.CAP_EXCEEDED;
            case REVERSED -> RewardApprovalAccrualState.REVERSED;
        };
    }
}
