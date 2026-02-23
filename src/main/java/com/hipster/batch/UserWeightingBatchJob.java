package com.hipster.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hipster.common.lock.DistributedLock;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserWeightingBatchJob {

    private final JobLauncher jobLauncher;
    private final Job weightingRecalculationJob;

    /**
     * 매일 새벽 2시에 모든 유저의 가중치를 재계산합니다.
     * TODO: Weighting_Algorithm.md에 명시된 45일 주기를 정확하게 구현해야 합니다.
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @DistributedLock(key = "weightingRecalculation", waitTime = 0, leaseTime = 300)
    public void runWeightingRecalculation() {
        log.info("Triggering user weighting recalculation Job due to schedule.");
        try {
            final JobParameters jobParameters = new JobParametersBuilder()
                    .addLong("time", System.currentTimeMillis())
                    .toJobParameters();
            jobLauncher.run(weightingRecalculationJob, jobParameters);
        } catch (Exception e) {
            log.error("User weighting recalculation Job failed.", e);
        }
    }
}
