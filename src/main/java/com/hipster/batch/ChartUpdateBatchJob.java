package com.hipster.batch;

import com.hipster.chart.service.ChartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChartUpdateBatchJob {

    private final ChartService chartService;

    /**
     * 매주 화요일 오전 9시에 차트 점수를 업데이트합니다.
     */
    @Scheduled(cron = "0 0 9 ? * TUE")
    public void runChartUpdate() {
        log.info("Triggering chart update batch job.");
        try {
            chartService.updateAllChartScores();
        } catch (Exception e) {
            log.error("Chart update batch job failed.", e);
        }
    }
}
