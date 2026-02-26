package com.hipster.moderation.repository;

import com.hipster.moderation.domain.ModerationQueue;
import com.hipster.moderation.domain.ModerationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModerationQueueRepository extends JpaRepository<ModerationQueue, Long>, JpaSpecificationExecutor<ModerationQueue> {

    List<ModerationQueue> findByStatusOrderByPriorityAscSubmittedAtAsc(ModerationStatus status, Pageable pageable);

    Long countByStatusAndPriority(ModerationStatus status, Integer priority);

    Page<ModerationQueue> findBySubmitterIdOrderBySubmittedAtDesc(Long submitterId, Pageable pageable);

    Optional<ModerationQueue> findByIdAndStatus(Long id, ModerationStatus status);

    Long countBySubmitterIdAndSubmittedAtAfter(Long submitterId, java.time.LocalDateTime submittedAt);

    Long countByEntityTypeAndEntityIdAndSubmittedAtAfter(com.hipster.moderation.domain.EntityType entityType, Long entityId, java.time.LocalDateTime submittedAt);

    void deleteBySubmitterId(Long submitterId);
}
