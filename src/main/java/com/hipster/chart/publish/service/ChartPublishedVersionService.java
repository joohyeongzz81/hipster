package com.hipster.chart.publish.service;

import com.hipster.chart.config.ChartPublishProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChartPublishedVersionService {

    private final StringRedisTemplate redisTemplate;
    private final ChartPublishProperties chartPublishProperties;
    private final ChartPublishStateService chartPublishStateService;

    public String getPublishedVersionOrLegacy() {
        if (!chartPublishProperties.isEnabled()) {
            return "legacy";
        }

        try {
            final String cached = redisTemplate.opsForValue().get(chartPublishProperties.getPublishedVersionCacheKey());
            if (StringUtils.hasText(cached)) {
                return cached;
            }
        } catch (Exception e) {
            log.warn("[ChartPublishedVersion] Redis read failed. reason={}", e.getMessage());
        }

        return chartPublishStateService.getCurrentVersion()
                .orElse("bootstrap");
    }

    public void cachePublishedVersion(final String version) {
        if (!chartPublishProperties.isEnabled() || !StringUtils.hasText(version)) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(chartPublishProperties.getPublishedVersionCacheKey(), version);
        } catch (Exception e) {
            log.warn("[ChartPublishedVersion] Redis write failed. reason={}", e.getMessage());
        }
    }
}
