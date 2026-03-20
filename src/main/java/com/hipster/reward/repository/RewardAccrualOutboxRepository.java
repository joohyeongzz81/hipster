package com.hipster.reward.repository;

import com.hipster.reward.domain.RewardAccrualOutbox;
import com.hipster.reward.domain.RewardAccrualOutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RewardAccrualOutboxRepository extends JpaRepository<RewardAccrualOutbox, Long> {

    Optional<RewardAccrualOutbox> findByApprovalIdAndCampaignCode(Long approvalId, String campaignCode);

    List<RewardAccrualOutbox> findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            List<RewardAccrualOutboxStatus> statuses,
            LocalDateTime nextAttemptAt,
            Pageable pageable
    );

    List<RewardAccrualOutbox> findByStatusAndProcessedAtIsNullAndDispatchedAtLessThanEqualOrderByDispatchedAtAsc(
            RewardAccrualOutboxStatus status,
            LocalDateTime dispatchedAt,
            Pageable pageable
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RewardAccrualOutbox o
               set o.status = :nextStatus,
                   o.dispatchedAt = :dispatchedAt,
                   o.nextAttemptAt = :nextAttemptAt,
                   o.lastError = :lastError
             where o.id = :outboxId
               and o.status in :currentStatuses
               and o.nextAttemptAt <= :referenceTime
            """)
    int updateStatusForDispatch(Long outboxId,
                                List<RewardAccrualOutboxStatus> currentStatuses,
                                RewardAccrualOutboxStatus nextStatus,
                                LocalDateTime referenceTime,
                                LocalDateTime dispatchedAt,
                                LocalDateTime nextAttemptAt,
                                String lastError);
}
