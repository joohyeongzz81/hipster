package com.hipster.chart.service;

import com.hipster.chart.publish.service.ChartPublishStateService;
import com.hipster.chart.repository.ChartScoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChartLastUpdatedServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ChartScoreRepository chartScoreRepository;

    @Mock
    private ChartPublishStateService chartPublishStateService;

    @Test
    @DisplayName("logical_as_of_at을 authoritative lastUpdated로 사용한다")
    void refreshFromDatabase_usesPublishedLogicalAsOf() {
        final LocalDateTime logicalAsOfAt = LocalDateTime.of(2026, 3, 14, 9, 30);
        given(chartPublishStateService.getCurrentLogicalAsOfAt()).willReturn(Optional.of(logicalAsOfAt));
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        final ChartLastUpdatedService service = new ChartLastUpdatedService(
                redisTemplate,
                chartScoreRepository,
                chartPublishStateService
        );

        final LocalDateTime result = service.refreshFromDatabase();

        assertThat(result).isEqualTo(logicalAsOfAt);
        verify(chartScoreRepository, never()).findMaxLastUpdated();
        verify(valueOperations).set(ChartLastUpdatedService.LAST_UPDATED_KEY, logicalAsOfAt.toString());
    }
}
