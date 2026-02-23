package com.hipster.batch.ui;

import com.hipster.batch.WeightingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/test/batch")
@RequiredArgsConstructor
public class TestBatchController {

    private final JobLauncher jobLauncher;
    private final Job weightingRecalculationJob;

    @PostMapping("/trigger")
    public ResponseEntity<String> triggerBatch() {
        log.info("Manual trigger for weighting recalculation batch started.");
        
        long startTime = System.currentTimeMillis();
        try {
            final JobParameters jobParameters = new JobParametersBuilder()
                    .addLong("time", System.currentTimeMillis())
                    .toJobParameters();
            jobLauncher.run(weightingRecalculationJob, jobParameters);
        } catch (Exception e) {
            log.error("Manual batch trigger failed.", e);
            return ResponseEntity.internalServerError().body("Batch failed: " + e.getMessage());
        }
        long endTime = System.currentTimeMillis();
        
        long duration = endTime - startTime;
        log.info("Batch finished in {} ms", duration);
        
        return ResponseEntity.ok("Batch Triggered and Completed in " + duration + " ms.");
    }
}
