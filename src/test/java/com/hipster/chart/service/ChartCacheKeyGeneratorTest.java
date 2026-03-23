package com.hipster.chart.service;

import com.hipster.chart.dto.request.ChartFilterRequest;
import com.hipster.chart.publish.service.ChartPublishedVersionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ChartCacheKeyGeneratorTest {

    @Mock
    private ChartPublishedVersionService chartPublishedVersionService;

    @Test
    @DisplayName("cache key prefix에는 현재 published version이 포함된다")
    void generateKey_includesPublishedVersion() {
        given(chartPublishedVersionService.getPublishedVersion()).willReturn("v20260314112233444");

        final ChartCacheKeyGenerator generator = new ChartCacheKeyGenerator(chartPublishedVersionService);

        final String key = generator.generateKey(ChartFilterRequest.empty(), 0);

        assertThat(key).isEqualTo("chart:v1:v20260314112233444:all:page:0");
    }
}
