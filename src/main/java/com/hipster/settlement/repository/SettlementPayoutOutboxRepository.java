package com.hipster.settlement.repository;

import com.hipster.settlement.domain.SettlementPayoutOutbox;
import com.hipster.settlement.domain.SettlementPayoutOutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SettlementPayoutOutboxRepository extends JpaRepository<SettlementPayoutOutbox, Long> {

    Optional<SettlementPayoutOutbox> findBySettlementRequestId(Long settlementRequestId);

    List<SettlementPayoutOutbox> findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            List<SettlementPayoutOutboxStatus> statuses,
            LocalDateTime nextAttemptAt,
            Pageable pageable
    );

    List<SettlementPayoutOutbox> findAllByStatusAndNextAttemptAtBefore(SettlementPayoutOutboxStatus status,
                                                                       LocalDateTime nextAttemptAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update SettlementPayoutOutbox outbox
               set outbox.status = :nextStatus,
                   outbox.dispatchedAt = :dispatchedAt,
                   outbox.nextAttemptAt = :nextAttemptAt,
                   outbox.lastError = :lastError
             where outbox.id = :outboxId
               and outbox.status in :currentStatuses
               and outbox.nextAttemptAt <= :referenceTime
            """)
    int updateStatusForDispatch(Long outboxId,
                                List<SettlementPayoutOutboxStatus> currentStatuses,
                                SettlementPayoutOutboxStatus nextStatus,
                                LocalDateTime referenceTime,
                                LocalDateTime dispatchedAt,
                                LocalDateTime nextAttemptAt,
                                String lastError);
}
