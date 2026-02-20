package com.hipster.moderation.service;

import com.hipster.global.dto.response.PaginationDto;
import com.hipster.global.exception.ConflictException;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.ForbiddenException;
import com.hipster.global.exception.NotFoundException;
import com.hipster.moderation.domain.ModerationQueue;
import com.hipster.moderation.domain.ModerationStatus;
import com.hipster.moderation.domain.RejectionReason;
import com.hipster.moderation.dto.response.ModerationQueueItemResponse;
import com.hipster.moderation.dto.response.ModerationQueueListResponse;
import com.hipster.moderation.dto.request.ModerationSubmitRequest;
import com.hipster.moderation.dto.response.ModerationSubmitResponse;
import com.hipster.moderation.repository.ModerationQueueRepository;
import com.hipster.release.domain.Release;
import com.hipster.release.repository.ReleaseRepository;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationQueueService {

    private final ModerationQueueRepository moderationQueueRepository;
    private final SpamDetectionService spamDetectionService;
    private final UserRepository userRepository;
    private final ReleaseRepository releaseRepository;

    @Transactional
    public ModerationSubmitResponse submit(final ModerationSubmitRequest request, final Long submitterId) {
        if (spamDetectionService.detectSpamPattern(submitterId, request.entityType(), request.entityId(), request.metaComment())) {
            return handleSpamSubmission(request, submitterId);
        }

        final ModerationQueue queueItem = ModerationQueue.builder()
                .entityType(request.entityType())
                .entityId(request.entityId())
                .submitterId(submitterId)
                .metaComment(request.metaComment())
                .priority(2)
                .build();

        final User user = userRepository.findById(submitterId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));

        if (user.getWeightingScore() > 0.8) {
            queueItem.autoApprove();
            publishEntity(queueItem);
            log.info("Auto-approved submission {} for User {}", queueItem.getId(), submitterId);
        }

        moderationQueueRepository.save(queueItem);

        final String estimatedTime = (queueItem.getStatus() == ModerationStatus.AUTO_APPROVED) ? "Instant" : "14 days";

        return new ModerationSubmitResponse(queueItem.getId(), queueItem.getStatus(), "Submission received.", estimatedTime);
    }

    @Transactional(readOnly = true)
    public ModerationQueueListResponse getModerationQueue(final ModerationStatus status, final Integer priority,
                                                          final int page, final int limit) {
        final ModerationStatus effectiveStatus = (status != null) ? status : ModerationStatus.PENDING;

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
                .map(item -> ModerationQueueItemResponse.of(item, userMap.get(item.getSubmitterId())))
                .toList();

        final long totalPending = moderationQueueRepository.count((root, query, cb) ->
                cb.equal(root.get("status"), ModerationStatus.PENDING));

        return new ModerationQueueListResponse(totalPending, items,
                new PaginationDto(page, limit, pageResult.getTotalElements(), pageResult.getTotalPages()));
    }

    @Transactional
    public ModerationQueueItemResponse claimQueueItem(final Long queueId, final Long moderatorId) {
        final ModerationQueue item = findQueueItemOrThrow(queueId);

        if (item.getStatus() == ModerationStatus.UNDER_REVIEW) {
            validateNotClaimedByOther(item, moderatorId);
        }

        item.assignModerator(moderatorId);
        moderationQueueRepository.save(item);

        final User submitter = userRepository.findById(item.getSubmitterId()).orElse(null);
        return ModerationQueueItemResponse.of(item, submitter);
    }

    @Transactional
    public void approve(final Long queueId, final Long moderatorId, final String comment) {
        final ModerationQueue item = findQueueItemOrThrow(queueId);
        validateModeratorOwnership(item, moderatorId);

        item.approve(comment);
        publishEntity(item);
        moderationQueueRepository.save(item);
    }

    @Transactional
    public void reject(final Long queueId, final Long moderatorId, final RejectionReason reason, final String comment) {
        final ModerationQueue item = findQueueItemOrThrow(queueId);
        validateNotClaimedByOther(item, moderatorId);

        item.reject(reason, comment);
        moderationQueueRepository.save(item);
    }

    private ModerationQueue findQueueItemOrThrow(final Long queueId) {
        return moderationQueueRepository.findById(queueId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.MODERATION_ITEM_NOT_FOUND));
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

    private ModerationSubmitResponse handleSpamSubmission(final ModerationSubmitRequest request, final Long submitterId) {
        final ModerationQueue spamItem = ModerationQueue.builder()
                .entityType(request.entityType())
                .entityId(request.entityId())
                .submitterId(submitterId)
                .metaComment(request.metaComment())
                .priority(2)
                .build();
        spamItem.reject(RejectionReason.SPAM_ABUSE, "Automated spam detection.");
        moderationQueueRepository.save(spamItem);
        return new ModerationSubmitResponse(spamItem.getId(), ModerationStatus.REJECTED, "Submission rejected due to spam suspicion.", "N/A");
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

    private void publishEntity(final ModerationQueue item) {
        switch (item.getEntityType()) {
            case RELEASE:
                if (item.getEntityId() != null) {
                    releaseRepository.findById(item.getEntityId()).ifPresent(Release::approve);
                }
                break;
            default:
                log.warn("Auto-publish not implemented for type: {}", item.getEntityType());
        }
    }
}
