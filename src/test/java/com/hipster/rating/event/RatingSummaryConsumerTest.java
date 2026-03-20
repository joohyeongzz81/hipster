package com.hipster.rating.event;

import com.hipster.rating.metrics.RatingMetricsRecorder;
import com.hipster.rating.service.RatingSummaryService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.IOException;
import java.time.LocalDateTime;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RatingSummaryConsumerTest {

    @Mock
    private RatingSummaryService ratingSummaryService;

    @Mock
    private RatingMetricsRecorder ratingMetricsRecorder;

    @Mock
    private Channel channel;

    @InjectMocks
    private RatingSummaryConsumer ratingSummaryConsumer;

    @Test
    void consumeRatingSummaryEvent_Success_AcksAndRecordsMetric() throws IOException {
        RatingEvent event = new RatingEvent(1L, 2L, 0.0, 4.0, true, false, 1.0, LocalDateTime.now());

        ratingSummaryConsumer.consumeRatingSummaryEvent(event, channel, 10L);

        verify(ratingSummaryService).applyRatingEvent(event);
        verify(channel).basicAck(10L, false);
        verify(ratingMetricsRecorder).recordConsumer("processed");
    }

    @Test
    void consumeRatingSummaryEvent_PermanentFailure_RoutesToDlqAndRecordsMetric() throws IOException {
        RatingEvent event = new RatingEvent(1L, 2L, 4.0, 0.0, false, true, 1.0, LocalDateTime.now());
        doThrow(new DataIntegrityViolationException("bad data"))
                .when(ratingSummaryService).applyRatingEvent(event);

        ratingSummaryConsumer.consumeRatingSummaryEvent(event, channel, 20L);

        verify(channel).basicNack(20L, false, false);
        verify(ratingMetricsRecorder).recordConsumer("permanent_failed");
    }

    @Test
    void consumeRatingSummaryEvent_TransientFailure_RequeuesAndRecordsMetric() throws IOException {
        RatingEvent event = new RatingEvent(1L, 2L, 4.0, 4.5, false, false, 1.0, LocalDateTime.now());
        doThrow(new RuntimeException("db down"))
                .when(ratingSummaryService).applyRatingEvent(event);

        ratingSummaryConsumer.consumeRatingSummaryEvent(event, channel, 30L);

        verify(channel).basicNack(30L, false, true);
        verify(ratingMetricsRecorder).recordConsumer("transient_failed");
    }
}
