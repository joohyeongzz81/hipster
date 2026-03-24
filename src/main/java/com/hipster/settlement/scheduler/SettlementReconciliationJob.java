package com.hipster.settlement.scheduler;

import com.hipster.settlement.service.SettlementReconciliationService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hipster.settlement.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class SettlementReconciliationJob {

    private final SettlementReconciliationService settlementReconciliationService;

    @Scheduled(fixedDelayString = "${hipster.settlement.reconciliation.fixed-delay-ms:10000}")
    @SchedulerLock(name = "settlementReconciliation", lockAtLeastFor = "10s", lockAtMostFor = "5m")
    public void runReconciliation() {
        settlementReconciliationService.reconcilePendingRequests();
    }
}
