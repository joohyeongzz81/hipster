package com.hipster.moderation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hipster.artist.repository.ArtistRepository;
import com.hipster.auth.UserRole;
import com.hipster.genre.repository.GenreRepository;
import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.domain.ModerationAuditEventType;
import com.hipster.moderation.domain.ModerationQueue;
import com.hipster.moderation.domain.ModerationStatus;
import com.hipster.moderation.domain.RejectionReason;
import com.hipster.moderation.dto.response.ModerationQueueItemResponse;
import com.hipster.moderation.dto.response.ModerationQueueListResponse;
import com.hipster.moderation.metrics.ModerationMetricsRecorder;
import com.hipster.moderation.repository.ModerationAuditTrailRepository;
import com.hipster.moderation.repository.ModerationQueueRepository;
import com.hipster.reward.service.RewardLedgerService;
import com.hipster.release.domain.Release;
import com.hipster.release.domain.ReleaseType;
import com.hipster.release.repository.ReleaseRepository;
import com.hipster.review.domain.Review;
import com.hipster.review.repository.ReviewRepository;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ModerationQueueServiceScenarioTest {

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
    private RewardLedgerService rewardLedgerService;

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
    @DisplayName("시나리오: claim 후 만료된 항목은 background recovery로 회수되고 다른 운영자가 다시 점유해 승인할 수 있다")
    void scenario_ExpiredClaimRecoveredThenApproved() {
        Long queueId = 100L;
        Long submitterId = 10L;
        Long firstModeratorId = 20L;
        Long secondModeratorId = 21L;

        ModerationQueue queueItem = queueItem(queueId, EntityType.REVIEW, 400L, submitterId, "Review moderation scenario");
        Review review = Review.builder()
                .userId(submitterId)
                .releaseId(500L)
                .content("A".repeat(80))
                .isPublished(false)
                .build();

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));
        given(moderationQueueRepository.findExpiredClaims(eq(ModerationStatus.UNDER_REVIEW), any(LocalDateTime.class)))
                .willReturn(List.of(queueItem));
        given(userRepository.findById(submitterId)).willReturn(Optional.empty());
        given(reviewRepository.findById(400L)).willReturn(Optional.of(review));

        ModerationQueueItemResponse firstClaim = moderationQueueService.claimQueueItem(queueId, firstModeratorId);
        assertThat(firstClaim.status()).isEqualTo(ModerationStatus.UNDER_REVIEW);
        assertThat(queueItem.getModeratorId()).isEqualTo(firstModeratorId);

        queueItem.assignModerator(firstModeratorId, LocalDateTime.now().minusHours(2), LocalDateTime.now().minusMinutes(90));
        int releasedCount = moderationQueueService.releaseExpiredClaims();

        assertThat(releasedCount).isEqualTo(1);
        assertThat(queueItem.getStatus()).isEqualTo(ModerationStatus.PENDING);
        assertThat(queueItem.getModeratorId()).isNull();

        ModerationQueueItemResponse secondClaim = moderationQueueService.claimQueueItem(queueId, secondModeratorId);
        assertThat(secondClaim.status()).isEqualTo(ModerationStatus.UNDER_REVIEW);
        assertThat(queueItem.getModeratorId()).isEqualTo(secondModeratorId);

        moderationQueueService.approve(queueId, secondModeratorId, "Publish this review");

        assertThat(queueItem.getStatus()).isEqualTo(ModerationStatus.APPROVED);
        assertThat(review.getIsPublished()).isTrue();

        InOrder auditAndMetricsOrder = inOrder(moderationAuditTrailRepository, moderationMetricsRecorder);
        auditAndMetricsOrder.verify(moderationAuditTrailRepository).save(argThat(argThatAudit(ModerationAuditEventType.CLAIMED)));
        auditAndMetricsOrder.verify(moderationMetricsRecorder).recordAction(ModerationAuditEventType.CLAIMED);
        auditAndMetricsOrder.verify(moderationAuditTrailRepository).save(argThat(argThatAudit(ModerationAuditEventType.LEASE_EXPIRED)));
        auditAndMetricsOrder.verify(moderationMetricsRecorder).recordAction(ModerationAuditEventType.LEASE_EXPIRED);
        auditAndMetricsOrder.verify(moderationAuditTrailRepository).save(argThat(argThatAudit(ModerationAuditEventType.CLAIMED)));
        auditAndMetricsOrder.verify(moderationMetricsRecorder).recordAction(ModerationAuditEventType.CLAIMED);
        auditAndMetricsOrder.verify(moderationAuditTrailRepository).save(argThat(argThatAudit(ModerationAuditEventType.APPROVED)));
        auditAndMetricsOrder.verify(moderationMetricsRecorder).recordAction(ModerationAuditEventType.APPROVED);
    }

    @Test
    @DisplayName("시나리오: 재할당된 항목은 SLA 초과 상태로 조회할 수 있고 새 담당자가 최종 반려할 수 있다")
    void scenario_ReassignedItemVisibleAsSlaBreachedThenRejected() {
        Long queueId = 101L;
        Long submitterId = 11L;
        Long firstModeratorId = 30L;
        Long secondModeratorId = 31L;
        Long adminId = 32L;

        ModerationQueue queueItem = queueItem(queueId, EntityType.RELEASE, 401L, submitterId, "Release moderation scenario");
        ReflectionTestUtils.setField(queueItem, "submittedAt", LocalDateTime.now().minusHours(48));

        User targetModerator = userWithRole(secondModeratorId, UserRole.MODERATOR);
        Release release = Release.builder()
                .artistId(77L)
                .title("Scenario release")
                .releaseType(ReleaseType.ALBUM)
                .releaseDate(LocalDate.now())
                .build();

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));
        given(userRepository.findById(submitterId)).willReturn(Optional.of(submitter(submitterId)));
        given(userRepository.findById(secondModeratorId)).willReturn(Optional.of(targetModerator));
        given(userRepository.findAllById(any())).willReturn(List.of(submitter(submitterId)));
        given(moderationQueueRepository.findAll(org.mockito.ArgumentMatchers.<Specification<ModerationQueue>>any(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(queueItem)));
        given(moderationQueueRepository.countByStatus(ModerationStatus.PENDING)).willReturn(0L);
        given(moderationQueueRepository.countByStatus(ModerationStatus.UNDER_REVIEW)).willReturn(1L);
        given(moderationQueueRepository.countByStatusInAndSubmittedAtLessThanEqual(any(), any(LocalDateTime.class)))
                .willReturn(1L);
        given(releaseRepository.findById(401L)).willReturn(Optional.of(release));

        moderationQueueService.claimQueueItem(queueId, firstModeratorId);

        ModerationQueueItemResponse reassigned = moderationQueueService.reassignQueueItem(
                queueId, adminId, UserRole.ADMIN, secondModeratorId);

        assertThat(reassigned.status()).isEqualTo(ModerationStatus.UNDER_REVIEW);
        assertThat(reassigned.moderatorId()).isEqualTo(secondModeratorId);

        ModerationQueueListResponse queueResponse = moderationQueueService.getModerationQueue(
                ModerationStatus.UNDER_REVIEW, null, 1, 20);

        assertThat(queueResponse.totalPending()).isZero();
        assertThat(queueResponse.totalUnderReview()).isEqualTo(1L);
        assertThat(queueResponse.totalSlaBreached()).isEqualTo(1L);
        assertThat(queueResponse.items()).hasSize(1);
        assertThat(queueResponse.items().get(0).slaBreached()).isTrue();
        assertThat(queueResponse.items().get(0).moderatorId()).isEqualTo(secondModeratorId);

        moderationQueueService.reject(queueId, secondModeratorId, RejectionReason.INCORRECT_INFORMATION, "Reject after reassignment");

        assertThat(queueItem.getStatus()).isEqualTo(ModerationStatus.REJECTED);
        assertThat(release.getStatus().name()).isEqualTo("DELETED");

        InOrder auditAndMetricsOrder = inOrder(moderationAuditTrailRepository, moderationMetricsRecorder);
        auditAndMetricsOrder.verify(moderationAuditTrailRepository).save(argThat(argThatAudit(ModerationAuditEventType.CLAIMED)));
        auditAndMetricsOrder.verify(moderationMetricsRecorder).recordAction(ModerationAuditEventType.CLAIMED);
        auditAndMetricsOrder.verify(moderationAuditTrailRepository).save(argThat(argThatAudit(ModerationAuditEventType.REASSIGNED)));
        auditAndMetricsOrder.verify(moderationMetricsRecorder).recordAction(ModerationAuditEventType.REASSIGNED);
        auditAndMetricsOrder.verify(moderationAuditTrailRepository).save(argThat(argThatAudit(ModerationAuditEventType.REJECTED)));
        auditAndMetricsOrder.verify(moderationMetricsRecorder).recordAction(ModerationAuditEventType.REJECTED);

        verify(moderationQueueRepository).countByStatus(ModerationStatus.PENDING);
        verify(moderationQueueRepository).countByStatus(ModerationStatus.UNDER_REVIEW);
    }

    private org.mockito.ArgumentMatcher<com.hipster.moderation.domain.ModerationAuditTrail> argThatAudit(
            final ModerationAuditEventType eventType) {
        return audit -> audit.getEventType() == eventType;
    }

    private ModerationQueue queueItem(final Long id, final EntityType entityType, final Long entityId,
                                      final Long submitterId, final String metaComment) {
        ModerationQueue queueItem = ModerationQueue.builder()
                .entityType(entityType)
                .entityId(entityId)
                .submitterId(submitterId)
                .metaComment(metaComment)
                .priority(2)
                .build();
        ReflectionTestUtils.setField(queueItem, "id", id);
        return queueItem;
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

    private User submitter(final Long userId) {
        User user = User.builder()
                .username("submitter-" + userId)
                .email("submitter-" + userId + "@example.com")
                .passwordHash("hashed-password")
                .build();
        ReflectionTestUtils.setField(user, "id", userId);
        return user;
    }
}
