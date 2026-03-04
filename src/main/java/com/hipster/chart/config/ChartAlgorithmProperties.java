package com.hipster.chart.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 차트 알고리즘 및 정책에 사용되는 상수 단일 관리.
 *
 * <pre>
 * chart:
 *   algorithm:
 *     prior-weight-m: 50.0   # 사전 가중치 — 전체 평균으로 수렴시키는 강도
 *     esoteric-multiplier-k: 1.0  # is_esoteric 판정 배수 (weightedCountSum < m * k)
 *     global-avg-fallback: 3.0    # 평점 데이터 없을 때 C 폴백값
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chart.algorithm")
public class ChartAlgorithmProperties {

    /** 사전 가중치 (Prior Weight). 투표 수가 적을 때 글로벌 평균으로 수렴시키는 강도. */
    private BigDecimal priorWeightM = BigDecimal.valueOf(50.0);

    /** is_esoteric 판정 배수. weightedCountSum < priorWeightM * esotericMultiplierK 이면 esoteric. */
    private BigDecimal esotericMultiplierK = BigDecimal.valueOf(1.0);

    /** 평점 데이터가 전혀 없을 때 C(글로벌 가중 평균) 폴백값. */
    private BigDecimal globalAvgFallback = BigDecimal.valueOf(3.0);
}
