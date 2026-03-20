package com.hipster.batch.antientropy;

import com.hipster.rating.metrics.RatingMetricsRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AntiEntropyBatchJobTest {

    @Mock
    private AntiEntropyQueryRepository antiEntropyQueryRepository;

    @Mock
    private RatingMetricsRecorder ratingMetricsRecorder;

    @InjectMocks
    private AntiEntropyBatchJob antiEntropyBatchJob;

    @Test
    void runAntiEntropyFull_Success_RecordsSuccessMetric() {
        when(antiEntropyQueryRepository.findAllReleaseIds()).thenReturn(List.of(1L, 2L, 3L));

        antiEntropyBatchJob.runAntiEntropyFull();

        verify(antiEntropyQueryRepository).reconcileChunk(anyList(), any(LocalDateTime.class));
        verify(ratingMetricsRecorder).recordAntiEntropy("success");
        verify(ratingMetricsRecorder, never()).recordAntiEntropy("failed");
    }

    @Test
    void runAntiEntropyFull_Failure_RecordsFailureMetric() {
        when(antiEntropyQueryRepository.findAllReleaseIds()).thenReturn(List.of(1L, 2L, 3L));
        doThrow(new IllegalStateException("chunk failed"))
                .when(antiEntropyQueryRepository).reconcileChunk(anyList(), any(LocalDateTime.class));

        antiEntropyBatchJob.runAntiEntropyFull();

        verify(ratingMetricsRecorder).recordAntiEntropy("failed");
        verify(ratingMetricsRecorder, never()).recordAntiEntropy("success");
    }
}
