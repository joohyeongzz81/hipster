package com.hipster.chart.service;

import com.hipster.chart.config.ChartPublishProperties;
import com.hipster.chart.publish.service.ChartPublishStateService;
import com.hipster.chart.repository.ChartScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChartLastUpdatedService {

    public static final String LAST_UPDATED_KEY = "chart-meta:last-updated:v1";

    private final StringRedisTemplate redisTemplate;
    private final ChartScoreRepository chartScoreRepository;
    private final ChartPublishProperties chartPublishProperties;
    private final ChartPublishStateService chartPublishStateService;

    @Transactional(readOnly = true)
    public LocalDateTime getLastUpdated() {
        final String cached = readCachedValue();
        if (StringUtils.hasText(cached)) {
            try {
                return LocalDateTime.parse(cached);
            } catch (DateTimeParseException e) {
                log.warn("[ChartLastUpdated] 캐시 값 파싱 실패, DB fallback 진행. value={}, reason={}", cached, e.getMessage());
            }
        }

        return refreshFromDatabase();
    }

    @Transactional(readOnly = true)
    public LocalDateTime refreshFromDatabase() {
        final LocalDateTime lastUpdated = resolveAuthoritativeLastUpdated();
        cacheLastUpdated(lastUpdated);
        return lastUpdated;
    }

    public void cacheLastUpdated(final LocalDateTime lastUpdated) {
        try {
            redisTemplate.opsForValue().set(LAST_UPDATED_KEY, lastUpdated.toString());
        } catch (Exception e) {
            log.warn("[ChartLastUpdated] Redis 저장 실패, 다음 요청에서 DB fallback 사용. reason={}", e.getMessage());
        }
    }

    private String readCachedValue() {
        try {
            return redisTemplate.opsForValue().get(LAST_UPDATED_KEY);
        } catch (Exception e) {
            log.warn("[ChartLastUpdated] Redis 조회 실패, DB fallback 진행. reason={}", e.getMessage());
            return null;
        }
    }

    private LocalDateTime resolveAuthoritativeLastUpdated() {
        if (chartPublishProperties.isEnabled()) {
            return chartPublishStateService.getCurrentLogicalAsOfAt()
                    .orElseGet(() -> chartScoreRepository.findMaxLastUpdated().orElse(LocalDateTime.now()));
        }

        return chartScoreRepository.findMaxLastUpdated()
                .orElse(LocalDateTime.now());
    }
}
