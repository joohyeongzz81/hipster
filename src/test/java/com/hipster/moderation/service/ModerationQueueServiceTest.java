package com.hipster.moderation.service;

import com.hipster.auth.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hipster.artist.repository.ArtistRepository;
import com.hipster.genre.repository.GenreRepository;
import com.hipster.global.dto.response.PagedResponse;
import com.hipster.global.exception.BadRequestException;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.ForbiddenException;
import com.hipster.global.exception.NotFoundException;
import com.hipster.moderation.domain.ModerationAuditEventType;
import com.hipster.moderation.domain.ModerationAuditTrail;
import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.domain.ModerationQueue;
import com.hipster.moderation.domain.ModerationStatus;
import com.hipster.moderation.domain.RejectionReason;
import com.hipster.moderation.dto.response.ModerationQueueItemResponse;
import com.hipster.moderation.dto.response.ModerationSubmitResponse;
import com.hipster.moderation.dto.response.UserModerationSubmissionResponse;
import com.hipster.moderation.metrics.ModerationMetricsRecorder;
import com.hipster.moderation.repository.ModerationAuditTrailRepository;
import com.hipster.moderation.repository.ModerationQueueRepository;
import com.hipster.reward.service.RewardAccrualOutboxService;
import com.hipster.release.domain.Release;
import com.hipster.release.domain.ReleaseType;
import com.hipster.release.repository.ReleaseRepository;
import com.hipster.review.domain.Review;
import com.hipster.review.repository.ReviewRepository;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ModerationQueueServiceTest {

    @InjectMocks
    private ModerationQueueService moderationQueueService;

    @Mock
    private ModerationQueueRepository moderationQueueRepository;

    @Mock
    private ModerationAuditTrailRepository moderationAuditTrailRepository;

    @Mock
    private ModerationMetricsRecorder moderationMetricsRecorder;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private SpamDetectionService spamDetectionService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ReleaseRepository releaseRepository;

    @Mock
    private ArtistRepository artistRepository;

    @Mock
    private GenreRepository genreRepository;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private RewardAccrualOutboxService rewardAccrualOutboxService;

    @Mock
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    @DisplayName("사용자 모더레이션 요청 목록 조회 - 정상 동작")
    void getUserSubmissions_Success() {
        Long submitterId = 1L;
        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 10L, submitterId, "Test release submission");
        queueItem.reject(RejectionReason.INCORRECT_INFORMATION, "Rejected due to policy");

        Page<ModerationQueue> mockPage = new PageImpl<>(List.of(queueItem));
        given(moderationQueueRepository.findBySubmitterIdOrderBySubmittedAtDesc(eq(submitterId), any(Pageable.class)))
                .willReturn(mockPage);

        PagedResponse<UserModerationSubmissionResponse> response = moderationQueueService.getUserSubmissions(submitterId, 1, 20);

        assertThat(response.data()).hasSize(1);
        UserModerationSubmissionResponse itemResponse = response.data().get(0);
        assertThat(itemResponse.status()).isEqualTo(ModerationStatus.REJECTED);
        assertThat(itemResponse.rejectionReason()).isEqualTo(RejectionReason.INCORRECT_INFORMATION.name());
        assertThat(itemResponse.moderatorComment()).isEqualTo("Rejected due to policy");
        assertThat(response.pagination().totalItems()).isEqualTo(1L);
    }

    @Test
    @DisplayName("이미 처리 완료된 항목은 다시 승인할 수 없다")
    void approve_AlreadyProcessed_ThrowsException() {
        Long queueId = 1L;
        Long submitterId = 10L;
        Long moderatorId = 20L;

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 100L, submitterId, "Approved already");
        queueItem.assignModerator(moderatorId, LocalDateTime.now(), LocalDateTime.now().plusMinutes(30));
        queueItem.approve("Approved once");

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));

        assertThatThrownBy(() -> moderationQueueService.approve(queueId, moderatorId, "Approve again"))
                .isInstanceOfSatisfying(BadRequestException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MODERATION_ALREADY_PROCESSED));

        verify(moderationQueueRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 처리 완료된 항목은 다시 반려할 수 없다")
    void reject_AlreadyProcessed_ThrowsException() {
        Long queueId = 2L;
        Long submitterId = 11L;
        Long moderatorId = 21L;

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 101L, submitterId, "Auto approved already");
        queueItem.autoApprove();

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));

        assertThatThrownBy(() -> moderationQueueService.reject(
                queueId, moderatorId, RejectionReason.INCORRECT_INFORMATION, "Reject again"))
                .isInstanceOfSatisfying(BadRequestException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MODERATION_ALREADY_PROCESSED));

        verify(moderationQueueRepository, never()).save(any());
    }

    @Test
    @DisplayName("만료된 점유는 다른 모더레이터가 다시 점유할 수 있다")
    void claimQueueItem_ExpiredClaim_ReassignsModerator() {
        Long queueId = 3L;
        Long submitterId = 12L;
        Long previousModeratorId = 30L;
        Long newModeratorId = 31L;
        LocalDateTime claimedAt = LocalDateTime.now().minusHours(2);

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 102L, submitterId, "Expired claim");
        queueItem.assignModerator(previousModeratorId, claimedAt, claimedAt.plusMinutes(30));

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));
        given(userRepository.findById(submitterId)).willReturn(Optional.empty());

        ModerationQueueItemResponse response = moderationQueueService.claimQueueItem(queueId, newModeratorId);

        assertThat(response.status()).isEqualTo(ModerationStatus.UNDER_REVIEW);
        assertThat(queueItem.getModeratorId()).isEqualTo(newModeratorId);
        assertThat(queueItem.getClaimedAt()).isNotNull();
        assertThat(queueItem.getClaimExpiresAt()).isNotNull();
        assertThat(Duration.between(queueItem.getClaimedAt(), queueItem.getClaimExpiresAt())).isEqualTo(Duration.ofMinutes(30));
        verify(moderationQueueRepository).save(queueItem);

        ArgumentCaptor<ModerationAuditTrail> auditCaptor = ArgumentCaptor.forClass(ModerationAuditTrail.class);
        verify(moderationAuditTrailRepository, times(2)).save(auditCaptor.capture());
        List<ModerationAuditTrail> audits = auditCaptor.getAllValues();

        ModerationAuditTrail leaseExpiredAudit = audits.get(0);
        assertThat(leaseExpiredAudit.getEventType()).isEqualTo(ModerationAuditEventType.LEASE_EXPIRED);
        assertThat(leaseExpiredAudit.getPreviousStatus()).isEqualTo(ModerationStatus.UNDER_REVIEW);
        assertThat(leaseExpiredAudit.getCurrentStatus()).isEqualTo(ModerationStatus.PENDING);
        assertThat(leaseExpiredAudit.getPreviousModeratorId()).isEqualTo(previousModeratorId);
        assertThat(leaseExpiredAudit.getCurrentModeratorId()).isNull();
        assertThat(leaseExpiredAudit.getReason()).isEqualTo("LEASE_EXPIRED");

        ModerationAuditTrail claimAudit = audits.get(1);
        assertThat(claimAudit.getEventType()).isEqualTo(ModerationAuditEventType.CLAIMED);
        assertThat(claimAudit.getPreviousStatus()).isEqualTo(ModerationStatus.PENDING);
        assertThat(claimAudit.getCurrentStatus()).isEqualTo(ModerationStatus.UNDER_REVIEW);
        assertThat(claimAudit.getPreviousModeratorId()).isNull();
        assertThat(claimAudit.getCurrentModeratorId()).isEqualTo(newModeratorId);
        assertThat(claimAudit.getReason()).isNull();

        verify(moderationMetricsRecorder).recordAction(ModerationAuditEventType.LEASE_EXPIRED);
        verify(moderationMetricsRecorder).recordAction(ModerationAuditEventType.CLAIMED);
    }

    @Test
    @DisplayName("점유가 만료되면 기존 모더레이터는 승인할 수 없다")
    void approve_ExpiredClaim_ThrowsNotClaimed() {
        Long queueId = 4L;
        Long submitterId = 13L;
        Long moderatorId = 32L;
        LocalDateTime claimedAt = LocalDateTime.now().minusHours(1);

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 103L, submitterId, "Expired before approve");
        queueItem.assignModerator(moderatorId, claimedAt, claimedAt.plusMinutes(30));

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));

        assertThatThrownBy(() -> moderationQueueService.approve(queueId, moderatorId, "Approve after expiry"))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MODERATION_NOT_CLAIMED));

        assertThat(queueItem.getStatus()).isEqualTo(ModerationStatus.PENDING);
        assertThat(queueItem.getModeratorId()).isNull();
        verify(moderationQueueRepository).save(queueItem);
        verify(moderationAuditTrailRepository).save(argThat(audit ->
                audit.getEventType() == ModerationAuditEventType.LEASE_EXPIRED
                        && audit.getPreviousStatus() == ModerationStatus.UNDER_REVIEW
                        && audit.getCurrentStatus() == ModerationStatus.PENDING
                        && moderatorId.equals(audit.getPreviousModeratorId())
                        && audit.getCurrentModeratorId() == null
        ));
    }

    @Test
    @DisplayName("모더레이터는 자신이 점유한 항목을 반납할 수 있다")
    void unclaimQueueItem_Success() {
        Long queueId = 5L;
        Long submitterId = 14L;
        Long moderatorId = 33L;
        LocalDateTime claimedAt = LocalDateTime.now().minusMinutes(5);

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 104L, submitterId, "Need unclaim");
        queueItem.assignModerator(moderatorId, claimedAt, claimedAt.plusMinutes(30));

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));

        moderationQueueService.unclaimQueueItem(queueId, moderatorId);

        assertThat(queueItem.getStatus()).isEqualTo(ModerationStatus.PENDING);
        assertThat(queueItem.getModeratorId()).isNull();
        assertThat(queueItem.getClaimedAt()).isNull();
        assertThat(queueItem.getClaimExpiresAt()).isNull();
        verify(moderationQueueRepository).save(queueItem);
        verify(moderationAuditTrailRepository).save(argThat(audit ->
                audit.getEventType() == ModerationAuditEventType.UNCLAIMED
                        && moderatorId.equals(audit.getActorId())
                        && audit.getPreviousStatus() == ModerationStatus.UNDER_REVIEW
                        && audit.getCurrentStatus() == ModerationStatus.PENDING
        ));
    }

    @Test
    @DisplayName("현재 점유자는 다른 운영자에게 담당 전환할 수 있다")
    void reassignQueueItem_Owner_Success() {
        Long queueId = 9L;
        Long submitterId = 22L;
        Long currentModeratorId = 40L;
        Long targetModeratorId = 41L;

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 108L, submitterId, "Reassign by owner");
        queueItem.assignModerator(currentModeratorId, LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusMinutes(25));

        User targetModerator = userWithRole(targetModeratorId, UserRole.MODERATOR);

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));
        given(userRepository.findById(targetModeratorId)).willReturn(Optional.of(targetModerator));
        given(userRepository.findById(submitterId)).willReturn(Optional.empty());

        ModerationQueueItemResponse response = moderationQueueService.reassignQueueItem(
                queueId, currentModeratorId, UserRole.MODERATOR, targetModeratorId);

        assertThat(response.status()).isEqualTo(ModerationStatus.UNDER_REVIEW);
        assertThat(queueItem.getModeratorId()).isEqualTo(targetModeratorId);
        assertThat(queueItem.getClaimedAt()).isNotNull();
        assertThat(queueItem.getClaimExpiresAt()).isNotNull();
        assertThat(Duration.between(queueItem.getClaimedAt(), queueItem.getClaimExpiresAt())).isEqualTo(Duration.ofMinutes(30));
        verify(moderationQueueRepository).save(queueItem);
        verify(moderationAuditTrailRepository).save(argThat(audit ->
                audit.getEventType() == ModerationAuditEventType.REASSIGNED
                        && currentModeratorId.equals(audit.getActorId())
                        && audit.getPreviousStatus() == ModerationStatus.UNDER_REVIEW
                        && audit.getCurrentStatus() == ModerationStatus.UNDER_REVIEW
                        && currentModeratorId.equals(audit.getPreviousModeratorId())
                        && targetModeratorId.equals(audit.getCurrentModeratorId())
        ));
    }

    @Test
    @DisplayName("관리자는 다른 운영자가 점유한 항목도 담당 전환할 수 있다")
    void reassignQueueItem_Admin_Success() {
        Long queueId = 10L;
        Long submitterId = 23L;
        Long currentModeratorId = 42L;
        Long adminId = 43L;
        Long targetModeratorId = 44L;

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 109L, submitterId, "Reassign by admin");
        queueItem.assignModerator(currentModeratorId, LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusMinutes(20));

        User targetModerator = userWithRole(targetModeratorId, UserRole.ADMIN);

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));
        given(userRepository.findById(targetModeratorId)).willReturn(Optional.of(targetModerator));
        given(userRepository.findById(submitterId)).willReturn(Optional.empty());

        moderationQueueService.reassignQueueItem(queueId, adminId, UserRole.ADMIN, targetModeratorId);

        assertThat(queueItem.getModeratorId()).isEqualTo(targetModeratorId);
        verify(moderationQueueRepository).save(queueItem);
        verify(moderationAuditTrailRepository).save(argThat(audit ->
                audit.getEventType() == ModerationAuditEventType.REASSIGNED
                        && adminId.equals(audit.getActorId())
                        && currentModeratorId.equals(audit.getPreviousModeratorId())
                        && targetModeratorId.equals(audit.getCurrentModeratorId())
        ));
    }

    @Test
    @DisplayName("점유하지 않은 모더레이터는 다른 사람의 항목을 담당 전환할 수 없다")
    void reassignQueueItem_NotOwnerModerator_ThrowsForbidden() {
        Long queueId = 11L;
        Long submitterId = 24L;
        Long currentModeratorId = 45L;
        Long anotherModeratorId = 46L;
        Long targetModeratorId = 47L;

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 110L, submitterId, "Forbidden reassign");
        queueItem.assignModerator(currentModeratorId, LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusMinutes(25));

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));

        assertThatThrownBy(() -> moderationQueueService.reassignQueueItem(
                queueId, anotherModeratorId, UserRole.MODERATOR, targetModeratorId))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MODERATION_CLAIMED_BY_OTHER));

        verify(moderationQueueRepository, never()).save(any());
    }

    @Test
    @DisplayName("담당 전환 대상은 모더레이터 또는 관리자여야 한다")
    void reassignQueueItem_TargetWithoutModerationRole_ThrowsBadRequest() {
        Long queueId = 12L;
        Long submitterId = 25L;
        Long currentModeratorId = 48L;
        Long targetUserId = 49L;

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 111L, submitterId, "Invalid target role");
        queueItem.assignModerator(currentModeratorId, LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusMinutes(25));

        User targetUser = userWithRole(targetUserId, UserRole.USER);

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));
        given(userRepository.findById(targetUserId)).willReturn(Optional.of(targetUser));

        assertThatThrownBy(() -> moderationQueueService.reassignQueueItem(
                queueId, currentModeratorId, UserRole.MODERATOR, targetUserId))
                .isInstanceOfSatisfying(BadRequestException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MODERATION_REASSIGN_TARGET_INVALID));

        verify(moderationQueueRepository, never()).save(any());
    }

    @Test
    @DisplayName("현재 담당자와 동일한 사용자에게는 담당 전환할 수 없다")
    void reassignQueueItem_SameTarget_ThrowsBadRequest() {
        Long queueId = 13L;
        Long submitterId = 26L;
        Long currentModeratorId = 50L;

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 112L, submitterId, "Same target");
        queueItem.assignModerator(currentModeratorId, LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusMinutes(25));

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));

        assertThatThrownBy(() -> moderationQueueService.reassignQueueItem(
                queueId, currentModeratorId, UserRole.MODERATOR, currentModeratorId))
                .isInstanceOfSatisfying(BadRequestException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MODERATION_REASSIGN_SAME_TARGET));

        verify(moderationQueueRepository, never()).save(any());
    }

    @Test
    @DisplayName("만료된 항목은 담당 전환 전에 만료 보정이 적용된 뒤 거절된다")
    void reassignQueueItem_ExpiredClaim_ThrowsBadRequest() {
        Long queueId = 14L;
        Long submitterId = 27L;
        Long currentModeratorId = 51L;
        Long targetModeratorId = 52L;
        LocalDateTime claimedAt = LocalDateTime.now().minusHours(1);

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 113L, submitterId, "Expired reassign");
        queueItem.assignModerator(currentModeratorId, claimedAt, claimedAt.plusMinutes(30));

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));

        assertThatThrownBy(() -> moderationQueueService.reassignQueueItem(
                queueId, currentModeratorId, UserRole.MODERATOR, targetModeratorId))
                .isInstanceOfSatisfying(BadRequestException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MODERATION_REASSIGN_NOT_ALLOWED));

        assertThat(queueItem.getStatus()).isEqualTo(ModerationStatus.PENDING);
        assertThat(queueItem.getModeratorId()).isNull();
        verify(moderationQueueRepository).save(queueItem);
        verify(moderationAuditTrailRepository).save(argThat(audit ->
                audit.getEventType() == ModerationAuditEventType.LEASE_EXPIRED
                        && currentModeratorId.equals(audit.getPreviousModeratorId())
                        && audit.getCurrentModeratorId() == null
        ));
    }

    @Test
    @DisplayName("대기열 조회는 전역 만료 점유 정리를 수행하지 않는다")
    void getModerationQueue_DoesNotReleaseExpiredClaimsBeforeListing() {
        Long submitterId = 15L;
        ModerationQueue pendingItem = queueItem(EntityType.RELEASE, 105L, submitterId, "Old pending");
        ModerationQueue underReviewItem = queueItem(EntityType.RELEASE, 106L, submitterId, "Fresh under review");
        underReviewItem.assignModerator(99L, LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusMinutes(20));
        ReflectionTestUtils.setField(pendingItem, "submittedAt", LocalDateTime.now().minusHours(48));
        ReflectionTestUtils.setField(underReviewItem, "submittedAt", LocalDateTime.now().minusHours(2));

        given(moderationQueueRepository.findAll(org.mockito.ArgumentMatchers.<Specification<ModerationQueue>>any(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(pendingItem, underReviewItem)));
        given(moderationQueueRepository.countByStatus(ModerationStatus.PENDING)).willReturn(1L);
        given(moderationQueueRepository.countByStatus(ModerationStatus.UNDER_REVIEW)).willReturn(1L);
        given(moderationQueueRepository.countByStatusInAndSubmittedAtLessThanEqual(any(), any(LocalDateTime.class)))
                .willReturn(1L);
        given(userRepository.findAllById(any())).willReturn(List.of());

        var response = moderationQueueService.getModerationQueue(null, null, 1, 20);

        assertThat(response.totalPending()).isEqualTo(1L);
        assertThat(response.totalUnderReview()).isEqualTo(1L);
        assertThat(response.totalSlaBreached()).isEqualTo(1L);
        assertThat(response.slaTargetHours()).isEqualTo(24L);
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).slaBreached()).isTrue();
        assertThat(response.items().get(0).submittedAgeHours()).isEqualTo(48L);
        assertThat(response.items().get(1).slaBreached()).isFalse();
        assertThat(response.items().get(1).submittedAgeHours()).isEqualTo(2L);

        verify(moderationQueueRepository, never()).findExpiredClaims(any(), any(LocalDateTime.class));
        verify(moderationQueueRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("만료 점유 회수 작업은 요청 유입 없이 만료된 점유를 정리한다")
    void releaseExpiredClaims_ReleasesExpiredItems() {
        Long submitterId = 20L;
        Long moderatorId = 38L;
        LocalDateTime claimedAt = LocalDateTime.now().minusHours(2);

        ModerationQueue expiredItem = queueItem(EntityType.RELEASE, 106L, submitterId, "Release by scheduler");
        expiredItem.assignModerator(moderatorId, claimedAt, claimedAt.plusMinutes(30));

        given(moderationQueueRepository.findExpiredClaims(eq(ModerationStatus.UNDER_REVIEW), any(LocalDateTime.class)))
                .willReturn(List.of(expiredItem));

        int releasedCount = moderationQueueService.releaseExpiredClaims();

        assertThat(releasedCount).isEqualTo(1);
        assertThat(expiredItem.getStatus()).isEqualTo(ModerationStatus.PENDING);
        assertThat(expiredItem.getModeratorId()).isNull();
        verify(moderationQueueRepository).saveAndFlush(expiredItem);
        verify(moderationAuditTrailRepository).save(argThat(audit ->
                audit.getEventType() == ModerationAuditEventType.LEASE_EXPIRED
                        && audit.getPreviousStatus() == ModerationStatus.UNDER_REVIEW
                        && audit.getCurrentStatus() == ModerationStatus.PENDING
                        && moderatorId.equals(audit.getPreviousModeratorId())
                        && audit.getCurrentModeratorId() == null
        ));
        verify(moderationMetricsRecorder).recordAction(ModerationAuditEventType.LEASE_EXPIRED);
    }

    @Test
    @DisplayName("만료 점유 회수 중 동시성 충돌이 나면 해당 항목은 건너뛴다")
    void releaseExpiredClaims_SkipsOptimisticLockConflict() {
        Long submitterId = 21L;
        Long moderatorId = 39L;
        LocalDateTime claimedAt = LocalDateTime.now().minusHours(2);

        ModerationQueue expiredItem = queueItem(EntityType.RELEASE, 107L, submitterId, "Conflict on release");
        expiredItem.assignModerator(moderatorId, claimedAt, claimedAt.plusMinutes(30));

        given(moderationQueueRepository.findExpiredClaims(eq(ModerationStatus.UNDER_REVIEW), any(LocalDateTime.class)))
                .willReturn(List.of(expiredItem));
        given(moderationQueueRepository.saveAndFlush(expiredItem))
                .willThrow(new ObjectOptimisticLockingFailureException(ModerationQueue.class, 107L));

        int releasedCount = moderationQueueService.releaseExpiredClaims();

        assertThat(releasedCount).isEqualTo(0);
        verify(moderationQueueRepository).saveAndFlush(expiredItem);
        verify(moderationAuditTrailRepository, never()).save(any());
    }

    @Test
    @DisplayName("리뷰 승인 시 실제 리뷰 공개 상태와 검수 상태를 함께 반영한다")
    void approve_Review_PublishesReviewAndMarksApproved() {
        Long queueId = 6L;
        Long submitterId = 16L;
        Long moderatorId = 35L;

        ModerationQueue queueItem = queueItem(EntityType.REVIEW, 200L, submitterId, "Publish review");
        queueItem.assignModerator(moderatorId, LocalDateTime.now(), LocalDateTime.now().plusMinutes(30));

        Review review = Review.builder()
                .userId(submitterId)
                .releaseId(300L)
                .content("A".repeat(60))
                .isPublished(false)
                .build();
        ReflectionTestUtils.setField(queueItem, "id", queueId);

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));
        given(reviewRepository.findById(200L)).willReturn(Optional.of(review));

        moderationQueueService.approve(queueId, moderatorId, "Looks good");

        assertThat(review.getIsPublished()).isTrue();
        assertThat(queueItem.getStatus()).isEqualTo(ModerationStatus.APPROVED);
        verify(moderationQueueRepository).save(queueItem);
        verify(rewardAccrualOutboxService).enqueueApprovedContribution(queueItem);
        verify(moderationAuditTrailRepository).save(argThat(audit ->
                audit.getEventType() == ModerationAuditEventType.APPROVED
                        && moderatorId.equals(audit.getActorId())
                        && audit.getPreviousStatus() == ModerationStatus.UNDER_REVIEW
                        && audit.getCurrentStatus() == ModerationStatus.APPROVED
                        && "Looks good".equals(audit.getComment())
        ));
    }

    @Test
    @DisplayName("릴리즈 반려 시 실제 릴리즈 상태와 검수 상태를 함께 반영한다")
    void reject_Release_DeletesReleaseAndMarksRejected() {
        Long queueId = 7L;
        Long submitterId = 17L;
        Long moderatorId = 36L;

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 201L, submitterId, "Reject release");
        queueItem.assignModerator(moderatorId, LocalDateTime.now(), LocalDateTime.now().plusMinutes(30));

        Release release = Release.builder()
                .artistId(10L)
                .title("Pending release")
                .releaseType(ReleaseType.ALBUM)
                .releaseDate(LocalDate.now())
                .build();

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));
        given(releaseRepository.findById(201L)).willReturn(Optional.of(release));

        moderationQueueService.reject(queueId, moderatorId, RejectionReason.INCORRECT_INFORMATION, "Rejected");

        assertThat(release.getStatus().name()).isEqualTo("DELETED");
        assertThat(queueItem.getStatus()).isEqualTo(ModerationStatus.REJECTED);
        verify(moderationQueueRepository).save(queueItem);
        verify(moderationAuditTrailRepository).save(argThat(audit ->
                audit.getEventType() == ModerationAuditEventType.REJECTED
                        && moderatorId.equals(audit.getActorId())
                        && audit.getPreviousStatus() == ModerationStatus.UNDER_REVIEW
                        && audit.getCurrentStatus() == ModerationStatus.REJECTED
                        && RejectionReason.INCORRECT_INFORMATION.name().equals(audit.getReason())
                        && "Rejected".equals(audit.getComment())
        ));
    }

    @Test
    @DisplayName("승인 대상 엔티티가 없으면 검수 상태를 완료로 바꾸지 않는다")
    void approve_MissingRelease_ThrowsNotFound() {
        Long queueId = 8L;
        Long submitterId = 18L;
        Long moderatorId = 37L;

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 202L, submitterId, "Missing release");
        queueItem.assignModerator(moderatorId, LocalDateTime.now(), LocalDateTime.now().plusMinutes(30));

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));
        given(releaseRepository.findById(202L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> moderationQueueService.approve(queueId, moderatorId, "Approve"))
                .isInstanceOfSatisfying(NotFoundException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RELEASE_NOT_FOUND));

        assertThat(queueItem.getStatus()).isEqualTo(ModerationStatus.UNDER_REVIEW);
        verify(moderationQueueRepository, never()).save(queueItem);
    }

    @Test
    @DisplayName("제출 스냅샷은 검수 대기열에 직렬화되어 저장된다")
    void submit_SerializesSnapshotAndSavesQueueItem() throws Exception {
        Long submitterId = 19L;
        User user = User.builder()
                .username("submitter")
                .email("submitter@example.com")
                .passwordHash("hashed-password")
                .build();

        given(objectMapper.writeValueAsString(any())).willReturn("{\"name\":\"Test Artist\"}");
        given(spamDetectionService.detectSpamPattern(eq(submitterId), eq(EntityType.ARTIST), eq(300L), any()))
                .willReturn(false);
        given(userRepository.findById(submitterId)).willReturn(Optional.of(user));
        given(moderationQueueRepository.save(any(ModerationQueue.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        moderationQueueService.submit(
                new com.hipster.moderation.dto.request.ModerationSubmitRequest(
                        EntityType.ARTIST,
                        300L,
                        "Artist submission for moderation review.",
                        Map.of("name", "Test Artist")
                ),
                submitterId
        );

        verify(moderationQueueRepository).save(argThat(item ->
                "{\"name\":\"Test Artist\"}".equals(item.getSubmissionSnapshot())
                        && item.getEntityType() == EntityType.ARTIST
                        && item.getEntityId().equals(300L)
        ));
    }

    @Test
    @DisplayName("스팸 자동 반려도 moderation audit 와 metrics 에 남는다")
    void submit_SpamSubmission_RecordsAuditAndMetrics() throws Exception {
        Long submitterId = 28L;
        Review review = Review.builder()
                .userId(submitterId)
                .releaseId(500L)
                .content("Spam".repeat(20))
                .isPublished(true)
                .build();

        given(objectMapper.writeValueAsString(any())).willReturn("{\"content\":\"Spam Review\"}");
        given(spamDetectionService.detectSpamPattern(eq(submitterId), eq(EntityType.REVIEW), eq(301L), any()))
                .willReturn(true);
        given(reviewRepository.findById(301L)).willReturn(Optional.of(review));
        given(moderationQueueRepository.save(any(ModerationQueue.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        ModerationSubmitResponse response = moderationQueueService.submit(
                new com.hipster.moderation.dto.request.ModerationSubmitRequest(
                        EntityType.REVIEW,
                        301L,
                        "Spam submission",
                        Map.of("content", "Spam Review")
                ),
                submitterId
        );

        assertThat(response.status()).isEqualTo(ModerationStatus.REJECTED);
        assertThat(review.getIsPublished()).isFalse();
        verify(moderationAuditTrailRepository).save(argThat(audit ->
                audit.getEventType() == ModerationAuditEventType.REJECTED
                        && audit.getActorId() == null
                        && audit.getPreviousStatus() == ModerationStatus.PENDING
                        && audit.getCurrentStatus() == ModerationStatus.REJECTED
                        && RejectionReason.SPAM_ABUSE.name().equals(audit.getReason())
                        && "Automated spam detection.".equals(audit.getComment())
        ));
        verify(moderationMetricsRecorder).recordAction(ModerationAuditEventType.REJECTED);
    }

    private ModerationQueue queueItem(final EntityType entityType, final Long entityId, final Long submitterId,
                                      final String metaComment) {
        return ModerationQueue.builder()
                .entityType(entityType)
                .entityId(entityId)
                .submitterId(submitterId)
                .metaComment(metaComment)
                .priority(2)
                .build();
    }

    private User userWithRole(final Long userId, final UserRole role) {
        User user = User.builder()
                .username("moderator-" + userId)
                .email("moderator-" + userId + "@example.com")
                .passwordHash("hashed-password")
                .build();
        ReflectionTestUtils.setField(user, "id", userId);
        ReflectionTestUtils.setField(user, "moderationRole", role);
        return user;
    }
}
