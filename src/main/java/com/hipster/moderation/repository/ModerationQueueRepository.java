package com.hipster.moderation.repository;

import com.hipster.moderation.domain.ModerationQueue;
import com.hipster.moderation.domain.ModerationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ModerationQueueRepository extends JpaRepository<ModerationQueue, Long>, JpaSpecificationExecutor<ModerationQueue> {

    List<ModerationQueue> findByStatusOrderByPriorityAscSubmittedAtAsc(ModerationStatus status, Pageable pageable);

    Long countByStatusAndPriority(ModerationStatus status, Integer priority);

    Long countByStatus(ModerationStatus status);

    Long countByStatusInAndSubmittedAtLessThanEqual(Collection<ModerationStatus> statuses,
                                                    LocalDateTime submittedAt);

    Page<ModerationQueue> findBySubmitterIdOrderBySubmittedAtDesc(Long submitterId, Pageable pageable);

    List<ModerationQueue> findBySubmitterIdAndStatusInOrderBySubmittedAtDesc(Long submitterId,
                                                                              Collection<ModerationStatus> statuses);

    Optional<ModerationQueue> findByIdAndStatus(Long id, ModerationStatus status);

    Long countBySubmitterIdAndSubmittedAtAfter(Long submitterId, java.time.LocalDateTime submittedAt);

    Long countByEntityTypeAndEntityIdAndSubmittedAtAfter(com.hipster.moderation.domain.EntityType entityType, Long entityId, java.time.LocalDateTime submittedAt);

    @Query("""
            select item
            from ModerationQueue item
            where item.status = :status
              and (item.claimExpiresAt is null or item.claimExpiresAt <= :referenceTime)
            """)
    List<ModerationQueue> findExpiredClaims(@Param("status") ModerationStatus status,
                                            @Param("referenceTime") LocalDateTime referenceTime);

    void deleteBySubmitterId(Long submitterId);
}
