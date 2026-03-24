package com.hipster.settlement.repository;

import com.hipster.settlement.domain.SettlementRequest;
import com.hipster.settlement.domain.SettlementRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SettlementRequestRepository extends JpaRepository<SettlementRequest, Long> {

    Optional<SettlementRequest> findByRequestNo(String requestNo);

    Optional<SettlementRequest> findByRequestNoAndUserId(String requestNo, Long userId);

    Optional<SettlementRequest> findFirstByProviderReference(String providerReference);

    Optional<SettlementRequest> findFirstByUserIdAndStatusInOrderByRequestedAtDesc(Long userId,
                                                                                    Collection<SettlementRequestStatus> statuses);

    List<SettlementRequest> findAllByStatusInOrderByRequestedAtAsc(Collection<SettlementRequestStatus> statuses);
}
