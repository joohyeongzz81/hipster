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

    public boolean detectSpamPattern(Long submitterId, EntityType entityType, Long entityId, String metaComment) {
        // 1. Check submission count in last 24 hours (> 10 is spam)
        long recentSubmissions = moderationQueueRepository.countBySubmitterIdAndSubmittedAtAfter(submitterId, LocalDateTime.now().minusDays(1));
        if (recentSubmissions > 10) {
            return true;
        }

        // 2. Check metaComment for banned phrases (e.g., "I am the artist")
        if (metaComment != null && metaComment.trim().equalsIgnoreCase("I am the artist")) {
            return true;
        }

        // 3. Check duplicate submissions (>= 3 for same entity in last 7 days)
        if (entityId != null) {
            long duplicateSubmissions = moderationQueueRepository.countByEntityTypeAndEntityIdAndSubmittedAtAfter(entityType, entityId, LocalDateTime.now().minusDays(7));
            if (duplicateSubmissions >= 3) {
                return true;
            }
        }

        return false;
    }
}
