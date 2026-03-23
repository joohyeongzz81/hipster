package com.hipster.batch.chart.benchmark.controller;

import com.hipster.batch.chart.benchmark.ChartProjectionWriteMode;
import com.hipster.batch.chart.benchmark.service.ChartBatchBenchmarkService;
import com.hipster.batch.chart.benchmark.service.ChartBatchRunService;
import com.hipster.chart.service.ChartElasticsearchIndexService;
import com.hipster.global.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/benchmarks/chart-batch")
@RequiredArgsConstructor
public class ChartBatchBenchmarkController {

    private final ChartBatchBenchmarkService chartBatchBenchmarkService;
    private final ChartBatchRunService chartBatchRunService;

    @GetMapping("/projection")
    public ResponseEntity<ApiResponse<ChartBatchBenchmarkService.ProjectionSampleResponse>> benchmarkProjection(
            @RequestParam(defaultValue = "0") final int startChunk,
            @RequestParam(defaultValue = "3") final int chunkCount,
            @RequestParam(defaultValue = "2000") final int chunkSize,
            @RequestParam(defaultValue = "UPSERT") final ChartProjectionWriteMode writeMode) throws Exception {
        final ChartBatchBenchmarkService.ProjectionSampleResponse response =
                chartBatchBenchmarkService.benchmarkProjectionSample(startChunk, chunkCount, chunkSize, writeMode);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/es")
    public ResponseEntity<ApiResponse<ChartElasticsearchIndexService.ElasticsearchIndexSample>> benchmarkElasticsearch(
            @RequestParam(defaultValue = "0") final int startPage,
            @RequestParam(defaultValue = "3") final int pageCount,
            @RequestParam(defaultValue = "2000") final int batchSize) {
        final ChartElasticsearchIndexService.ElasticsearchIndexSample response =
                chartBatchBenchmarkService.benchmarkElasticsearchSample(startPage, pageCount, batchSize);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/publish")
    public ResponseEntity<ApiResponse<ChartBatchBenchmarkService.PublishSampleResponse>> benchmarkPublish() {
        final ChartBatchBenchmarkService.PublishSampleResponse response =
                chartBatchBenchmarkService.benchmarkPublishSample();
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/estimate")
    public ResponseEntity<ApiResponse<ChartBatchBenchmarkService.PipelineEstimateResponse>> benchmarkEstimate(
            @RequestParam(defaultValue = "2") final int sampleChunkCount,
            @RequestParam(defaultValue = "2000") final int chunkSize,
            @RequestParam(defaultValue = "UPSERT") final ChartProjectionWriteMode writeMode,
            @RequestParam(defaultValue = "2") final int esSamplePageCount,
            @RequestParam(defaultValue = "2000") final int esBatchSize) throws Exception {
        final ChartBatchBenchmarkService.PipelineEstimateResponse response =
                chartBatchBenchmarkService.benchmarkPipelineEstimate(
                        sampleChunkCount,
                        chunkSize,
                        writeMode,
                        esSamplePageCount,
                        esBatchSize
                );
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/precheck")
    public ResponseEntity<ApiResponse<ChartBatchRunService.PrecheckSnapshot>> precheck() {
        return ResponseEntity.ok(ApiResponse.ok(chartBatchRunService.collectPrecheckSnapshot()));
    }

    @PostMapping("/bootstrap-published-state")
    public ResponseEntity<ApiResponse<ChartBatchRunService.PublishStateSnapshot>> bootstrapPublishedState() {
        return ResponseEntity.ok(ApiResponse.ok(chartBatchRunService.bootstrapPublishedState()));
    }

    @PostMapping("/full-run")
    public ResponseEntity<ApiResponse<ChartBatchRunService.FullBatchBenchmarkRunSummary>> fullRun(
            @RequestParam(defaultValue = "LIGHT_STAGE_INSERT") final ChartProjectionWriteMode writeMode,
            @RequestParam(defaultValue = "2000") final int projectionChunkSize,
            @RequestParam(defaultValue = "20000") final int esBatchSize,
            @RequestParam(required = false) final String benchmarkIndexName) throws Exception {
        final ChartBatchRunService.FullBatchBenchmarkRunSummary response =
                chartBatchRunService.runFullBatchBenchmark(writeMode, projectionChunkSize, esBatchSize, benchmarkIndexName);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/publish-run")
    public ResponseEntity<ApiResponse<ChartBatchRunService.PublishJobRunSummary>> publishRun() throws Exception {
        return ResponseEntity.ok(ApiResponse.ok(chartBatchRunService.runPublishJob()));
    }
}
