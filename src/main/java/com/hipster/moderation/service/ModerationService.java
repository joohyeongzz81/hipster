package com.hipster.moderation.service;

import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.NotFoundException;
import com.hipster.moderation.domain.ModerationQueue;
import com.hipster.moderation.dto.SubmitRequest;
import com.hipster.moderation.repository.ModerationQueueRepository;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ModerationService {

    private final ModerationQueueRepository moderationQueueRepository;
    private final UserRepository userRepository;

    public void submit(Long submitterId, SubmitRequest request) {
        User user = userRepository.findById(submitterId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));

        ModerationQueue queueItem = ModerationQueue.builder()
                .entityType(request.entityType())
                .entityId(request.entityId())
                .submitterId(submitterId)
                .metaComment(request.metaComment())
                .priority(2) // Default priority P2
                .build();

        // Check for trusted user auto-approval (weightingScore > 0.8)
        if (user.getWeightingScore() > 0.8) {
            queueItem.autoApprove();
        }

        moderationQueueRepository.save(queueItem);
    }
}
