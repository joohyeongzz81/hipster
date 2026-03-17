package com.hipster.moderation.job;

import com.hipster.moderation.service.ModerationQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationClaimTimeoutRecoveryJob {

    private final ModerationQueueService moderationQueueService;

    @Scheduled(cron = "${hipster.moderation.claim-timeout-recovery-cron:0 * * * * ?}")
    @SchedulerLock(name = "moderationClaimTimeoutRecovery", lockAtLeastFor = "10s", lockAtMostFor = "5m")
    public void recoverExpiredClaims() {
        final int releasedCount = moderationQueueService.releaseExpiredClaims();
        if (releasedCount > 0) {
            log.info("[ModerationClaimTimeoutRecovery] Released {} expired claims.", releasedCount);
        }
    }
}
