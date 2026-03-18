package com.hipster.moderation.service;

import com.hipster.auth.UserRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hipster.artist.repository.ArtistRepository;
import com.hipster.genre.repository.GenreRepository;
import com.hipster.global.dto.response.PaginationDto;
import com.hipster.global.exception.BadRequestException;
import com.hipster.global.exception.BusinessException;
import com.hipster.global.exception.ConflictException;
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
import com.hipster.moderation.dto.response.ModerationQueueListResponse;
import com.hipster.moderation.dto.request.ModerationSubmitRequest;
import com.hipster.moderation.dto.response.ModerationSubmitResponse;
import com.hipster.moderation.dto.response.UserModerationSubmissionResponse;
import com.hipster.global.dto.response.PagedResponse;
import com.hipster.moderation.metrics.ModerationMetricsRecorder;
import com.hipster.moderation.repository.ModerationAuditTrailRepository;
import com.hipster.moderation.repository.ModerationQueueRepository;
import com.hipster.release.repository.ReleaseRepository;
import com.hipster.reward.service.RewardLedgerService;
import com.hipster.review.repository.ReviewRepository;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationQueueService {

    private static final Duration CLAIM_TTL = Duration.ofMinutes(30);
    private static final String LEASE_EXPIRED_REASON = "LEASE_EXPIRED";

    @Value("${hipster.moderation.sla-hours:24}")
    private long moderationSlaHours = 24;

    private final ModerationAuditTrailRepository moderationAuditTrailRepository;
    private final ModerationMetricsRecorder moderationMetricsRecorder;
    private final TransactionTemplate transactionTemplate;
    private final ModerationQueueRepository moderationQueueRepository;
    private final SpamDetectionService spamDetectionService;
    private final UserRepository userRepository;
    private final ReleaseRepository releaseRepository;
    private final ArtistRepository artistRepository;
    private final GenreRepository genreRepository;
    private final ReviewRepository reviewRepository;
    private final RewardLedgerService rewardLedgerService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ModerationSubmitResponse submit(final ModerationSubmitRequest request, final Long submitterId) {
        final String submissionSnapshot = serializeSnapshot(request.submissionSnapshot());

        if (spamDetectionService.detectSpamPattern(submitterId, request.entityType(), request.entityId(), request.metaComment())) {
            return handleSpamSubmission(request, submitterId, submissionSnapshot);
        }

        final ModerationQueue queueItem = ModerationQueue.builder()
                .entityType(request.entityType())
                .entityId(request.entityId())
                .submitterId(submitterId)
                .metaComment(request.metaComment())
                .priority(2)
                .submissionSnapshot(submissionSnapshot)
                .build();

        final User user = userRepository.findById(submitterId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));

        moderationQueueRepository.save(queueItem);

        if (isEligibleForAutoApproval(request, user)) {
            applyApprovalEntityState(queueItem);
            queueItem.autoApprove();
            moderationQueueRepository.save(queueItem);
            log.info("Auto-approved submission {} for User {}", queueItem.getId(), submitterId);
        }

        final String estimatedTime = (queueItem.getStatus() == ModerationStatus.AUTO_APPROVED) ? "Instant" : "14 days";

        return new ModerationSubmitResponse(queueItem.getId(), queueItem.getStatus(), "Submission received.", estimatedTime);
    }

    @Transactional
    public ModerationQueueListResponse getModerationQueue(final ModerationStatus status, final Integer priority,
                                                          final int page, final int limit) {
        final ModerationStatus effectiveStatus = (status != null) ? status : ModerationStatus.PENDING;
        final LocalDateTime now = LocalDateTime.now();

        final Sort sort = Sort.by(Sort.Direction.ASC, "priority", "submittedAt");
        final Pageable pageable = PageRequest.of(Math.max(0, page - 1), limit, sort);

        final Specification<ModerationQueue> spec = buildQueueSpecification(effectiveStatus, priority);
        final Page<ModerationQueue> pageResult = moderationQueueRepository.findAll(spec, pageable);

        final Set<Long> submitterIds = pageResult.getContent().stream()
                .map(ModerationQueue::getSubmitterId)
                .collect(Collectors.toSet());
        final Map<Long, User> userMap = userRepository.findAllById(submitterIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        final List<ModerationQueueItemResponse> items = pageResult.getContent().stream()
                .map(item -> ModerationQueueItemResponse.of(item, userMap.get(item.getSubmitterId()), now, moderationSlaHours))
                .toList();

        final long totalPending = countQueueItemsByStatus(ModerationStatus.PENDING);
        final long totalUnderReview = countQueueItemsByStatus(ModerationStatus.UNDER_REVIEW);
        final long totalSlaBreached = moderationQueueRepository.countByStatusInAndSubmittedAtLessThanEqual(
                List.of(ModerationStatus.PENDING, ModerationStatus.UNDER_REVIEW),
                now.minusHours(moderationSlaHours)
        );

        return new ModerationQueueListResponse(totalPending, totalUnderReview, totalSlaBreached, moderationSlaHours, items,
                new PaginationDto(page, limit, pageResult.getTotalElements(), pageResult.getTotalPages()));
    }

    @Transactional(readOnly = true)
    public PagedResponse<UserModerationSubmissionResponse> getUserSubmissions(final Long submitterId, final int page, final int limit) {
        final Sort sort = Sort.by(Sort.Direction.DESC, "submittedAt");
        final Pageable pageable = PageRequest.of(Math.max(0, page - 1), limit, sort);

        final Page<ModerationQueue> pageResult = moderationQueueRepository.findBySubmitterIdOrderBySubmittedAtDesc(submitterId, pageable);

        final List<UserModerationSubmissionResponse> items = pageResult.getContent().stream()
                .map(UserModerationSubmissionResponse::from)
                .toList();

        return new PagedResponse<>(items, new PaginationDto(page, limit, pageResult.getTotalElements(), pageResult.getTotalPages()));
    }

    @Transactional
    public ModerationQueueItemResponse claimQueueItem(final Long queueId, final Long moderatorId) {
        final ModerationQueue item = findQueueItemOrThrow(queueId);
        final LocalDateTime now = LocalDateTime.now();

        validateNotAlreadyProcessed(item);
        final ModerationStatus previousStatus = item.getStatus();
        final Long previousModeratorId = item.getModeratorId();
        final boolean expiredBeforeClaim = releaseClaimIfExpired(item, now);

        if (expiredBeforeClaim) {
            recordAuditTrail(
                    item.getId(),
                    ModerationAuditEventType.LEASE_EXPIRED,
                    null,
                    previousStatus,
                    item.getStatus(),
                    previousModeratorId,
                    item.getModeratorId(),
                    LEASE_EXPIRED_REASON,
                    null,
                    now
            );
        }

        final ModerationStatus claimPreviousStatus = item.getStatus();
        final Long claimPreviousModeratorId = item.getModeratorId();

        if (item.getStatus() == ModerationStatus.UNDER_REVIEW) {
            validateNotClaimedByOther(item, moderatorId);
        }

        item.assignModerator(moderatorId, now, now.plus(CLAIM_TTL));
        moderationQueueRepository.save(item);
        recordAuditTrail(
                item.getId(),
                ModerationAuditEventType.CLAIMED,
                moderatorId,
                claimPreviousStatus,
                item.getStatus(),
                claimPreviousModeratorId,
                item.getModeratorId(),
                null,
                null,
                now
        );

        final User submitter = userRepository.findById(item.getSubmitterId()).orElse(null);
        return ModerationQueueItemResponse.of(item, submitter, now, moderationSlaHours);
    }

    @Transactional
    public void approve(final Long queueId, final Long moderatorId, final String comment) {
        final long startedAt = System.nanoTime();
        String outcome = "success";

        try {
            final ModerationQueue item = findQueueItemOrThrow(queueId);
            validateNotAlreadyProcessed(item);
            persistExpiredClaimReleaseIfNeeded(item);
            validateModeratorOwnership(item, moderatorId);

            final ModerationStatus previousStatus = item.getStatus();
            final Long previousModeratorId = item.getModeratorId();
            final LocalDateTime occurredAt = LocalDateTime.now();
            applyApprovalEntityState(item);
            item.approve(comment);
            moderationQueueRepository.save(item);
            rewardLedgerService.accrueApprovedContribution(item);
            recordAuditTrail(
                    item.getId(),
                    ModerationAuditEventType.APPROVED,
                    moderatorId,
                    previousStatus,
                    item.getStatus(),
                    previousModeratorId,
                    item.getModeratorId(),
                    null,
                    comment,
                    occurredAt
            );
        } catch (RuntimeException exception) {
            outcome = resolveApproveOutcome(exception);
            throw exception;
        } finally {
            moderationMetricsRecorder.recordApproveDuration(outcome, System.nanoTime() - startedAt);
        }
    }

    @Transactional
    public void reject(final Long queueId, final Long moderatorId, final RejectionReason reason, final String comment) {
        final ModerationQueue item = findQueueItemOrThrow(queueId);
        validateNotAlreadyProcessed(item);
        persistExpiredClaimReleaseIfNeeded(item);
        validateModeratorOwnership(item, moderatorId);

        final ModerationStatus previousStatus = item.getStatus();
        final Long previousModeratorId = item.getModeratorId();
        final LocalDateTime occurredAt = LocalDateTime.now();
        applyRejectionEntityState(item);
        item.reject(reason, comment);
        moderationQueueRepository.save(item);
        recordAuditTrail(
                item.getId(),
                ModerationAuditEventType.REJECTED,
                moderatorId,
                previousStatus,
                item.getStatus(),
                previousModeratorId,
                item.getModeratorId(),
                reason.name(),
                comment,
                occurredAt
        );
    }

    @Transactional
    public void unclaimQueueItem(final Long queueId, final Long moderatorId) {
        final ModerationQueue item = findQueueItemOrThrow(queueId);
        validateNotAlreadyProcessed(item);
        persistExpiredClaimReleaseIfNeeded(item);
        validateModeratorOwnership(item, moderatorId);

        final ModerationStatus previousStatus = item.getStatus();
        final Long previousModeratorId = item.getModeratorId();
        final LocalDateTime occurredAt = LocalDateTime.now();
        item.releaseClaim();
        moderationQueueRepository.save(item);
        recordAuditTrail(
                item.getId(),
                ModerationAuditEventType.UNCLAIMED,
                moderatorId,
                previousStatus,
                item.getStatus(),
                previousModeratorId,
                item.getModeratorId(),
                null,
                null,
                occurredAt
        );
    }

    @Transactional
    public ModerationQueueItemResponse reassignQueueItem(final Long queueId, final Long requesterId,
                                                         final UserRole requesterRole, final Long targetModeratorId) {
        final ModerationQueue item = findQueueItemOrThrow(queueId);
        validateNotAlreadyProcessed(item);
        persistExpiredClaimReleaseIfNeeded(item);
        validateReassignableState(item);
        validateReassignRequester(item, requesterId, requesterRole);
        validateReassignTarget(item, targetModeratorId);

        final ModerationStatus previousStatus = item.getStatus();
        final Long previousModeratorId = item.getModeratorId();
        final LocalDateTime now = LocalDateTime.now();
        item.assignModerator(targetModeratorId, now, now.plus(CLAIM_TTL));
        moderationQueueRepository.save(item);
        recordAuditTrail(
                item.getId(),
                ModerationAuditEventType.REASSIGNED,
                requesterId,
                previousStatus,
                item.getStatus(),
                previousModeratorId,
                item.getModeratorId(),
                null,
                null,
                now
        );

        final User submitter = userRepository.findById(item.getSubmitterId()).orElse(null);
        return ModerationQueueItemResponse.of(item, submitter, now, moderationSlaHours);
    }

    private ModerationQueue findQueueItemOrThrow(final Long queueId) {
        return moderationQueueRepository.findById(queueId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.MODERATION_ITEM_NOT_FOUND));
    }

    private void validateNotAlreadyProcessed(final ModerationQueue item) {
        if (item.isProcessed()) {
            throw new BadRequestException(ErrorCode.MODERATION_ALREADY_PROCESSED);
        }
    }

    public int releaseExpiredClaims() {
        final List<ModerationQueue> expiredClaims = moderationQueueRepository
                .findExpiredClaims(ModerationStatus.UNDER_REVIEW, LocalDateTime.now());

        int releasedCount = 0;
        for (final ModerationQueue expiredClaim : expiredClaims) {
            if (releaseExpiredClaimSafely(expiredClaim)) {
                releasedCount++;
            }
        }

        if (releasedCount > 0) {
            log.info("Released {} expired moderation claims.", releasedCount);
        }
        return releasedCount;
    }

    private void persistExpiredClaimReleaseIfNeeded(final ModerationQueue item) {
        final LocalDateTime now = LocalDateTime.now();
        final ModerationStatus previousStatus = item.getStatus();
        final Long previousModeratorId = item.getModeratorId();
        if (releaseClaimIfExpired(item, now)) {
            moderationQueueRepository.save(item);
            recordAuditTrail(
                    item.getId(),
                    ModerationAuditEventType.LEASE_EXPIRED,
                    null,
                    previousStatus,
                    item.getStatus(),
                    previousModeratorId,
                    item.getModeratorId(),
                    LEASE_EXPIRED_REASON,
                    null,
                    now
            );
        }
    }

    private boolean releaseClaimIfExpired(final ModerationQueue item, final LocalDateTime referenceTime) {
        if (!item.isClaimExpired(referenceTime)) {
            return false;
        }

        item.releaseClaim();
        return true;
    }

    private boolean releaseExpiredClaimSafely(final ModerationQueue expiredClaim) {
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                final ModerationStatus previousStatus = expiredClaim.getStatus();
                final Long previousModeratorId = expiredClaim.getModeratorId();
                final LocalDateTime occurredAt = LocalDateTime.now();

                expiredClaim.releaseClaim();
                moderationQueueRepository.saveAndFlush(expiredClaim);
                recordAuditTrail(
                        expiredClaim.getId(),
                        ModerationAuditEventType.LEASE_EXPIRED,
                        null,
                        previousStatus,
                        expiredClaim.getStatus(),
                        previousModeratorId,
                        expiredClaim.getModeratorId(),
                        LEASE_EXPIRED_REASON,
                        null,
                        occurredAt
                );
                return true;
            }));
        } catch (ObjectOptimisticLockingFailureException exception) {
            log.info("Skipped expired moderation claim release due to concurrent update. queueId={}", expiredClaim.getId());
            return false;
        }
    }

    private void validateModeratorOwnership(final ModerationQueue item, final Long moderatorId) {
        if (item.getModeratorId() == null) {
            throw new ForbiddenException(ErrorCode.MODERATION_NOT_CLAIMED);
        }
        if (!item.getModeratorId().equals(moderatorId)) {
            throw new ForbiddenException(ErrorCode.MODERATION_CLAIMED_BY_OTHER);
        }
    }

    private void validateNotClaimedByOther(final ModerationQueue item, final Long moderatorId) {
        if (item.getModeratorId() != null && !item.getModeratorId().equals(moderatorId)) {
            throw new ConflictException(ErrorCode.MODERATION_CLAIMED_BY_OTHER);
        }
    }

    private void validateReassignableState(final ModerationQueue item) {
        if (item.getStatus() != ModerationStatus.UNDER_REVIEW || item.getModeratorId() == null) {
            throw new BadRequestException(ErrorCode.MODERATION_REASSIGN_NOT_ALLOWED);
        }
    }

    private void validateReassignRequester(final ModerationQueue item, final Long requesterId, final UserRole requesterRole) {
        if (requesterRole == UserRole.ADMIN) {
            return;
        }
        validateModeratorOwnership(item, requesterId);
    }

    private void validateReassignTarget(final ModerationQueue item, final Long targetModeratorId) {
        if (item.getModeratorId() != null && item.getModeratorId().equals(targetModeratorId)) {
            throw new BadRequestException(ErrorCode.MODERATION_REASSIGN_SAME_TARGET);
        }

        final User targetModerator = userRepository.findById(targetModeratorId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.TARGET_USER_NOT_FOUND));

        if (!targetModerator.hasRole(UserRole.MODERATOR) && !targetModerator.hasRole(UserRole.ADMIN)) {
            throw new BadRequestException(ErrorCode.MODERATION_REASSIGN_TARGET_INVALID);
        }
    }

    private void recordAuditTrail(final Long queueItemId,
                                  final ModerationAuditEventType eventType,
                                  final Long actorId,
                                  final ModerationStatus previousStatus,
                                  final ModerationStatus currentStatus,
                                  final Long previousModeratorId,
                                  final Long currentModeratorId,
                                  final String reason,
                                  final String comment,
                                  final LocalDateTime occurredAt) {
        moderationAuditTrailRepository.save(ModerationAuditTrail.of(
                queueItemId,
                eventType,
                actorId,
                previousStatus,
                currentStatus,
                previousModeratorId,
                currentModeratorId,
                reason,
                comment,
                occurredAt
        ));
        moderationMetricsRecorder.recordAction(eventType);
    }

    private String resolveApproveOutcome(final RuntimeException exception) {
        if (exception instanceof BusinessException businessException) {
            return businessException.getErrorCode().name().toLowerCase(Locale.ROOT);
        }

        return exception.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    private ModerationSubmitResponse handleSpamSubmission(final ModerationSubmitRequest request, final Long submitterId,
                                                          final String submissionSnapshot) {
        final LocalDateTime occurredAt = LocalDateTime.now();
        final ModerationQueue spamItem = ModerationQueue.builder()
                .entityType(request.entityType())
                .entityId(request.entityId())
                .submitterId(submitterId)
                .metaComment(request.metaComment())
                .priority(2)
                .submissionSnapshot(submissionSnapshot)
                .build();
        applyRejectionEntityState(spamItem);
        final ModerationStatus previousStatus = spamItem.getStatus();
        spamItem.reject(RejectionReason.SPAM_ABUSE, "Automated spam detection.");
        moderationQueueRepository.save(spamItem);
        recordAuditTrail(
                spamItem.getId(),
                ModerationAuditEventType.REJECTED,
                null,
                previousStatus,
                spamItem.getStatus(),
                null,
                null,
                RejectionReason.SPAM_ABUSE.name(),
                "Automated spam detection.",
                occurredAt
        );
        return new ModerationSubmitResponse(spamItem.getId(), ModerationStatus.REJECTED, "Submission rejected due to spam suspicion.", "N/A");
    }

    private boolean isEligibleForAutoApproval(final ModerationSubmitRequest request, final User user) {
        if (request.entityType() != EntityType.RELEASE) {
            return false;
        }
        if (user.getWeightingScore() == null || user.getWeightingScore() <= 0.8) {
            return false;
        }

        // 현재 도메인에는 커뮤니티 찬성표/hold 데이터가 없으므로,
        // 요구사항을 어기며 과도하게 자동 승인하지 않도록 보수적으로 처리한다.
        return false;
    }

    private Specification<ModerationQueue> buildQueueSpecification(final ModerationStatus status, final Integer priority) {
        return (root, query, cb) -> {
            final List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), status));
            if (priority != null) {
                predicates.add(cb.equal(root.get("priority"), priority));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private long countQueueItemsByStatus(final ModerationStatus status) {
        return moderationQueueRepository.countByStatus(status);
    }

    private void applyApprovalEntityState(final ModerationQueue item) {
        final Long entityId = requireEntityId(item);

        switch (item.getEntityType()) {
            case RELEASE -> releaseRepository.findById(entityId)
                    .orElseThrow(() -> new NotFoundException(ErrorCode.RELEASE_NOT_FOUND))
                    .approve();
            case ARTIST -> artistRepository.findById(entityId)
                    .orElseThrow(() -> new NotFoundException(ErrorCode.ARTIST_NOT_FOUND))
                    .approve();
            case GENRE -> genreRepository.findById(entityId)
                    .orElseThrow(() -> new NotFoundException(ErrorCode.GENRE_NOT_FOUND))
                    .approve();
            case REVIEW -> reviewRepository.findById(entityId)
                    .orElseThrow(() -> new NotFoundException(ErrorCode.REVIEW_NOT_FOUND))
                    .publish();
        }
    }

    private void applyRejectionEntityState(final ModerationQueue item) {
        final Long entityId = requireEntityId(item);

        switch (item.getEntityType()) {
            case RELEASE -> releaseRepository.findById(entityId)
                    .orElseThrow(() -> new NotFoundException(ErrorCode.RELEASE_NOT_FOUND))
                    .delete();
            case ARTIST -> artistRepository.findById(entityId)
                    .orElseThrow(() -> new NotFoundException(ErrorCode.ARTIST_NOT_FOUND))
                    .delete();
            case GENRE -> genreRepository.findById(entityId)
                    .orElseThrow(() -> new NotFoundException(ErrorCode.GENRE_NOT_FOUND))
                    .delete();
            case REVIEW -> reviewRepository.findById(entityId)
                    .orElseThrow(() -> new NotFoundException(ErrorCode.REVIEW_NOT_FOUND))
                    .unpublish();
        }
    }

    private Long requireEntityId(final ModerationQueue item) {
        if (item.getEntityId() == null) {
            throw new BadRequestException(ErrorCode.BAD_REQUEST);
        }
        return item.getEntityId();
    }

    private String serializeSnapshot(final Object snapshot) {
        if (snapshot == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize moderation snapshot.", exception);
        }
    }
}
