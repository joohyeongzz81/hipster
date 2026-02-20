package com.hipster.moderation.service;

import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.repository.ModerationQueueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpamDetectionService {

    private final ModerationQueueRepository moderationQueueRepository;

    public boolean detectSpamPattern(final Long submitterId, final EntityType entityType,
                                     final Long entityId, final String metaComment) {
        if (isExcessiveSubmissions(submitterId)) {
            return true;
        }

        if (isBannedPhrase(metaComment)) {
            return true;
        }

        if (entityId != null && isDuplicateSubmission(entityType, entityId)) {
            return true;
        }

        return false;
    }

    private boolean isExcessiveSubmissions(final Long submitterId) {
        final long recentSubmissions = moderationQueueRepository
                .countBySubmitterIdAndSubmittedAtAfter(submitterId, LocalDateTime.now().minusDays(1));
        return recentSubmissions > 10;
    }

    private boolean isBannedPhrase(final String metaComment) {
        return metaComment != null && metaComment.trim().equalsIgnoreCase("I am the artist");
    }

    private boolean isDuplicateSubmission(final EntityType entityType, final Long entityId) {
        final long duplicateSubmissions = moderationQueueRepository
                .countByEntityTypeAndEntityIdAndSubmittedAtAfter(entityType, entityId, LocalDateTime.now().minusDays(7));
        return duplicateSubmissions >= 3;
    }
}
