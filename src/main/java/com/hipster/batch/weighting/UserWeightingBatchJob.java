package com.hipster.batch.weighting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserWeightingBatchJob {

    private static final int CYCLE_DAYS = 45;
    private static final String JOB_NAME = "weightingRecalculationJob";

    private final JobLauncher jobLauncher;
    private final Job weightingRecalculationJob;
    private final JobExplorer jobExplorer;

    /**
     * 매일 새벽 2시에 45일 경과 여부를 확인하여 가중치 재계산 배치를 실행합니다.
     * Spring cron은 "매 N일" 표현을 지원하지 않으므로,
     * JobExplorer로 마지막 성공 실행일을 조회하여 45일이 지났을 때만 실제 Job을 수행합니다.
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @SchedulerLock(name = "weightingRecalculationLock", lockAtLeastFor = "30s", lockAtMostFor = "5m")
    public void runWeightingRecalculation() {
        if (!isCycleDue()) {
            log.info("유저 가중치 배치: 마지막 실행으로부터 {}일 미경과, 건너뜀.", CYCLE_DAYS);
            return;
        }

        log.info("유저 가중치 배치: {}일 주기 도달, Job 실행.", CYCLE_DAYS);
        try {
            final JobParameters jobParameters = new JobParametersBuilder()
                    .addString("run.date", LocalDate.now().toString())
                    .addLong("timestamp", System.currentTimeMillis())
                    .toJobParameters();
            jobLauncher.run(weightingRecalculationJob, jobParameters);
        } catch (Exception e) {
            log.error("유저 가중치 배치 Job 실행 실패.", e);
        }
    }

    /**
     * 마지막 성공 실행일로부터 CYCLE_DAYS(45일)가 경과했는지 확인합니다.
     * 실행 이력이 없으면 최초 실행으로 간주 → true 반환.
     */
    private boolean isCycleDue() {
        final List<JobInstance> instances = jobExplorer.getJobInstances(JOB_NAME, 0, Integer.MAX_VALUE);
        if (instances.isEmpty()) {
            return true;
        }

        return instances.stream()
                .flatMap(instance -> jobExplorer.getJobExecutions(instance).stream())
                .filter(exec -> exec.getStatus() == BatchStatus.COMPLETED && exec.getEndTime() != null)
                .map(JobExecution::getEndTime)
                .max(Comparator.naturalOrder())
                .map(lastEndTime -> {
                    LocalDate lastRunDate = lastEndTime.toLocalDate();
                    long daysSinceLast = ChronoUnit.DAYS.between(lastRunDate, LocalDate.now());
                    log.info("유저 가중치 배치: 마지막 성공 실행 = {}, 경과일 = {}일", lastRunDate, daysSinceLast);
                    return daysSinceLast >= CYCLE_DAYS;
                })
                .orElse(true); // COMPLETED 이력 없으면 실행
    }
}

