package com.hipster.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserWeightingBatchJob {

    private final WeightingService weightingService;

    /**
     * 매일 새벽 2시에 모든 유저의 가중치를 재계산합니다.
     * TODO: Weighting_Algorithm.md에 명시된 45일 주기를 정확하게 구현해야 합니다.
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void runWeightingRecalculation() {
        log.info("Triggering user weighting recalculation due to schedule.");
        try {
            weightingService.recalculateWeightings();
        } catch (Exception e) {
            log.error("User weighting recalculation job failed.", e);
        }
    }
}
