package com.hipster.moderation.service;

import com.hipster.global.dto.PaginationDto;
import com.hipster.global.exception.ConflictException;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.ForbiddenException;
import com.hipster.global.exception.NotFoundException;
import com.hipster.moderation.domain.ModerationQueue;
import com.hipster.moderation.domain.ModerationStatus;
import com.hipster.moderation.domain.RejectionReason;
import com.hipster.moderation.dto.ModerationQueueItemResponse;
import com.hipster.moderation.dto.ModerationQueueListResponse;
import com.hipster.moderation.dto.ModerationSubmitRequest;
import com.hipster.moderation.dto.ModerationSubmitResponse;
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
@Transactional
public class ModerationQueueService {

    private final ModerationQueueRepository moderationQueueRepository;
    private final SpamDetectionService spamDetectionService;
    private final UserRepository userRepository;
    private final ReleaseRepository releaseRepository;

    public ModerationSubmitResponse submit(ModerationSubmitRequest request, Long submitterId) {
        if (spamDetectionService.detectSpamPattern(submitterId, request.entityType(), request.entityId(), request.metaComment())) {
            ModerationQueue spamItem = ModerationQueue.builder()
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

        ModerationQueue queueItem = ModerationQueue.builder()
                .entityType(request.entityType())
                .entityId(request.entityId())
                .submitterId(submitterId)
                .metaComment(request.metaComment())
                .priority(2)
                .build();

        User user = userRepository.findById(submitterId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));

        if (user.getWeightingScore() > 0.8) {
            queueItem.autoApprove();
            publishEntity(queueItem);
            log.info("Auto-approved submission {} for User {}", queueItem.getId(), submitterId);
        }

        moderationQueueRepository.save(queueItem);

        String estimatedTime = "14 days";
        if (queueItem.getStatus() == ModerationStatus.AUTO_APPROVED) {
            estimatedTime = "Instant";
        }

        return new ModerationSubmitResponse(queueItem.getId(), queueItem.getStatus(), "Submission received.", estimatedTime);
    }

    @Transactional(readOnly = true)
    public ModerationQueueListResponse getModerationQueue(ModerationStatus status, Integer priority, int page, int limit) {
        if (status == null) status = ModerationStatus.PENDING;

        Sort sort = Sort.by(Sort.Direction.ASC, "priority", "submittedAt");
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), limit, sort);

        ModerationStatus finalStatus = status;
        Specification<ModerationQueue> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), finalStatus));
            if (priority != null) {
                predicates.add(cb.equal(root.get("priority"), priority));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<ModerationQueue> pageResult = moderationQueueRepository.findAll(spec, pageable);

        Set<Long> submitterIds = pageResult.getContent().stream()
                .map(ModerationQueue::getSubmitterId)
                .collect(Collectors.toSet());
        Map<Long, User> userMap = userRepository.findAllById(submitterIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<ModerationQueueItemResponse> items = pageResult.getContent().stream()
                .map(item -> ModerationQueueItemResponse.of(item, userMap.get(item.getSubmitterId())))
                .toList();

        long totalPending = moderationQueueRepository.count((root, query, cb) ->
                cb.equal(root.get("status"), ModerationStatus.PENDING));

        return new ModerationQueueListResponse(totalPending, items,
                new PaginationDto(page, limit, pageResult.getTotalElements(), pageResult.getTotalPages()));
    }

    public ModerationQueueItemResponse claimQueueItem(Long queueId, Long moderatorId) {
        ModerationQueue item = moderationQueueRepository.findById(queueId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));

        if (item.getStatus() == ModerationStatus.UNDER_REVIEW) {
            if (item.getModeratorId() != null && !item.getModeratorId().equals(moderatorId)) {
                throw new ConflictException(ErrorCode.ALREADY_UNDER_REVIEW);
            }
        }

        item.assignModerator(moderatorId);
        moderationQueueRepository.save(item);

        User submitter = userRepository.findById(item.getSubmitterId()).orElse(null);
        return ModerationQueueItemResponse.of(item, submitter);
    }

    public void approve(Long queueId, Long moderatorId, String comment) {
        ModerationQueue item = moderationQueueRepository.findById(queueId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));

        if (item.getModeratorId() != null && !item.getModeratorId().equals(moderatorId)) {
            throw new ForbiddenException(ErrorCode.ACCESS_DENIED);
        }
        if (item.getModeratorId() == null) {
            throw new ForbiddenException(ErrorCode.ACCESS_DENIED); // Must claim first
        }

        item.approve(comment);
        publishEntity(item);
        moderationQueueRepository.save(item);
    }

    public void reject(Long queueId, Long moderatorId, RejectionReason reason, String comment) {
        ModerationQueue item = moderationQueueRepository.findById(queueId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));

        if (item.getModeratorId() != null && !item.getModeratorId().equals(moderatorId)) {
            throw new ForbiddenException(ErrorCode.ACCESS_DENIED);
        }

        item.reject(reason, comment);
        moderationQueueRepository.save(item);
    }

    private void publishEntity(ModerationQueue item) {
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
