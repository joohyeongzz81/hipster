package com.hipster.chart.service;

import com.hipster.chart.config.ChartPublishProperties;
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
    @DisplayName("publish 모드가 켜지면 cache key prefix에 published version이 포함된다")
    void generateKey_includesPublishedVersionWhenPublishEnabled() {
        final ChartPublishProperties properties = new ChartPublishProperties();
        properties.setEnabled(true);

        given(chartPublishedVersionService.getPublishedVersionOrLegacy()).willReturn("v20260314112233444");

        final ChartCacheKeyGenerator generator = new ChartCacheKeyGenerator(properties, chartPublishedVersionService);

        final String key = generator.generateKey(ChartFilterRequest.empty(), 0);

        assertThat(key).isEqualTo("chart:v1:v20260314112233444:all:page:0");
    }

    @Test
    @DisplayName("legacy 모드에서는 기존 cache key prefix를 유지한다")
    void generateKey_keepsLegacyPrefixWhenPublishDisabled() {
        final ChartPublishProperties properties = new ChartPublishProperties();
        properties.setEnabled(false);

        final ChartCacheKeyGenerator generator = new ChartCacheKeyGenerator(properties, chartPublishedVersionService);

        final String key = generator.generateKey(ChartFilterRequest.empty(), 1);

        assertThat(key).isEqualTo("chart:v1:all:page:1");
    }
}
