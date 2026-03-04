package com.hipster.batch.chart;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChartUpdateBatchJob {

    private final JobLauncher jobLauncher;

    @Qualifier("chartUpdateJob")
    private final Job chartUpdateJob;

    /**
     * 매주 화요일 오전 9시에 차트 점수를 업데이트합니다.
     * ShedLock으로 멀티 인스턴스 중복 실행을 방지합니다.
     */
    @Scheduled(cron = "0 0 9 ? * TUE")
    @SchedulerLock(name = "chartUpdateLock", lockAtLeastFor = "30s", lockAtMostFor = "30m")
    public void runChartUpdate() {
        log.info("[CHART BATCH] 차트 업데이트 Job 실행 시작.");
        try {
            final JobParameters jobParameters = new JobParametersBuilder()
                    .addString("run.date", LocalDate.now().toString())
                    .addLong("timestamp", System.currentTimeMillis())
                    .toJobParameters();
            jobLauncher.run(chartUpdateJob, jobParameters);
        } catch (Exception e) {
            log.error("[CHART BATCH] 차트 업데이트 Job 실행 실패.", e);
        }
    }
}

