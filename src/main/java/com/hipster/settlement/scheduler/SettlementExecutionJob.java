package com.hipster.settlement.scheduler;

import com.hipster.settlement.service.SettlementExecutionService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hipster.settlement.execution.enabled", havingValue = "true", matchIfMissing = true)
public class SettlementExecutionJob {

    private final SettlementExecutionService settlementExecutionService;

    @Scheduled(fixedDelayString = "${hipster.settlement.execution.fixed-delay-ms:5000}")
    @SchedulerLock(name = "settlementExecutionDispatch", lockAtLeastFor = "5s", lockAtMostFor = "3m")
    public void dispatchPendingRequests() {
        settlementExecutionService.dispatchPendingRequests();
    }
}
