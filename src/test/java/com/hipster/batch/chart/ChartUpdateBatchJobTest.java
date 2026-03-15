package com.hipster.batch.chart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StopWatch;

import java.time.LocalDate;

@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "spring.batch.jdbc.initialize-schema=always"
})
@ActiveProfiles("local")
class ChartUpdateBatchJobTest {

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("chartUpdateJob")
    private Job chartUpdateJob;

    @Test
    @DisplayName("챕터 1 기준 배치 실행 시간 측정")
    void measureChartUpdateBatchJobExecutionTime() throws Exception {
        // given
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("run.date", LocalDate.now().toString())
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

        StopWatch stopWatch = new StopWatch();

        System.out.println("=========================================");
        System.out.println("차트 업데이트 배치 측정 시작 (챕터 1 기준)");
        System.out.println("=========================================");

        // when
        stopWatch.start();
        org.springframework.batch.core.JobExecution jobExecution = jobLauncher.run(chartUpdateJob, jobParameters);
        stopWatch.stop();

        // then
        System.out.println("=========================================");
        System.out.println("차트 업데이트 배치 측정 완료");
        System.out.println("총 소요 시간: " + stopWatch.getTotalTimeMillis() + " ms");
        System.out.println("배치 최종 상태: " + jobExecution.getExitStatus().getExitCode());
        System.out.println("=========================================");

        org.junit.jupiter.api.Assertions.assertEquals(
                org.springframework.batch.core.ExitStatus.COMPLETED,
                jobExecution.getExitStatus(),
                "배치 실행 중 예외가 발생하여 실패(롤백)했습니다. 로그를 확인하세요."
        );
    }
}
