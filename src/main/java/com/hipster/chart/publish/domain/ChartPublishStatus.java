package com.hipster.chart.publish.domain;

public enum ChartPublishStatus {
    IDLE,
    GENERATING,
    VALIDATING,
    PUBLISHING,
    PUBLISHED,
    FAILED,
    ROLLED_BACK
}
