package com.hipster.rating;

import java.math.BigDecimal;

/**
 * 베이지안 평균 산출에 사용되는 공통 상수.
 * - M (전체 모평균): 평점 데이터가 없을 때 기본으로 수렴할 글로벌 평균값
 * - C (보정 계수, Prior Weight): 신뢰할 수 있는 평균을 얻기 위한 최소 가중 기준
 */
public final class BayesianConstants {

    public static final BigDecimal M = BigDecimal.valueOf(3.5);
    public static final BigDecimal C = BigDecimal.valueOf(50);

    private BayesianConstants() {}
}
