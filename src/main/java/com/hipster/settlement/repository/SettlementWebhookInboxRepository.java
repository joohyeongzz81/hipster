package com.hipster.settlement.repository;

import com.hipster.settlement.domain.SettlementWebhookInbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SettlementWebhookInboxRepository extends JpaRepository<SettlementWebhookInbox, Long> {

    boolean existsByProviderNameAndProviderEventId(String providerName, String providerEventId);

    Optional<SettlementWebhookInbox> findByProviderNameAndProviderEventId(String providerName, String providerEventId);
}
