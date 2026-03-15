package com.hipster.chart.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "chart.publish")
public class ChartPublishProperties {

    private boolean enabled = false;
    private String chartName = "weekly_chart";
    private String stageTableName = "chart_scores_stage";
    private String previousTableName = "chart_scores_prev";
    private String publishedVersionCacheKey = "chart-meta:published-version:v1";
    private String aliasName;

    public String resolveAliasName(final String baseIndexName) {
        if (StringUtils.hasText(aliasName)) {
            return aliasName;
        }
        return baseIndexName + "_published";
    }
}
