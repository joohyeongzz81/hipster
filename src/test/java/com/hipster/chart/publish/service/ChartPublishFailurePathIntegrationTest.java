package com.hipster.chart.publish.service;

import com.hipster.batch.chart.dto.ChartScoreDto;
import com.hipster.batch.chart.repository.ChartScoreQueryRepository;
import com.hipster.chart.config.ChartPublishProperties;
import com.hipster.chart.publish.domain.ChartPublishState;
import com.hipster.chart.publish.domain.ChartValidationStatus;
import com.hipster.chart.service.ChartElasticsearchIndexService;
import com.hipster.chart.service.ChartLastUpdatedService;
import com.hipster.rating.event.RatingSummaryConsumer;
import com.hipster.release.domain.ReleaseType;
import com.hipster.user.event.UserActivityConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;

@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.batch.jdbc.initialize-schema=always",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "chart.publish.chart-name=weekly_chart"
})
@Testcontainers
@ActiveProfiles("test")
class ChartPublishFailurePathIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("hipster_chart_publish_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("chartUpdateJob")
    private Job chartUpdateJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChartPublishProperties chartPublishProperties;

    @Autowired
    private ChartScoreQueryRepository chartScoreQueryRepository;

    @Autowired
    private ChartPublishStateService chartPublishStateService;

    @Autowired
    private ChartPublishOrchestratorService chartPublishOrchestratorService;

    @Autowired
    private ChartPublishedVersionService chartPublishedVersionService;

    @Autowired
    private ChartLastUpdatedService chartLastUpdatedService;

    @MockBean
    private ChartElasticsearchIndexService chartElasticsearchIndexService;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private RatingSummaryConsumer ratingSummaryConsumer;

    @MockBean
    private UserActivityConsumer userActivityConsumer;

    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM chart_publish_history");
        jdbcTemplate.execute("DELETE FROM chart_publish_state");
        jdbcTemplate.execute("DROP TABLE IF EXISTS chart_scores_failed");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + chartPublishProperties.getPreviousTableName());
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + chartPublishProperties.getStageTableName());
        jdbcTemplate.execute("DELETE FROM chart_scores");

        valueOperations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(redisTemplate.keys("chart:v1:*")).willReturn(Collections.emptySet());
        given(chartElasticsearchIndexService.buildCandidateIndexName(anyString()))
                .willAnswer(invocation -> "chart_scores_candidate_" + invocation.getArgument(0));
        doNothing().when(chartElasticsearchIndexService).rebuildCandidateIndex(anyString(), anyInt());
    }

    @Test
    @DisplayName("validation 실패 시 publish path는 중단되고 published state와 metadata는 바뀌지 않는다")
    void validationFailure_keepsPublishedStateAndSkipsMetadataPublish() throws Exception {
        publishBaselineVersion("v20260314100000000", LocalDateTime.of(2026, 3, 14, 10, 0));

        given(chartElasticsearchIndexService.countDocumentsByVersion(anyString())).willReturn(0L);
        given(chartElasticsearchIndexService.isIndexSearchableByVersion(anyString())).willReturn(false);

        final JobExecution execution = jobLauncher.run(
                chartUpdateJob,
                new JobParametersBuilder()
                        .addLong("ts", System.currentTimeMillis())
                        .toJobParameters()
        );

        final ChartPublishState state = chartPublishStateService.requireState();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(state.getCurrentVersion()).isEqualTo("v20260314100000000");
        assertThat(state.getCandidateVersion()).isNotBlank();
        assertThat(state.getLastValidationStatus()).isEqualTo(ChartValidationStatus.FAILED);
        assertThat(state.getStatus().name()).isNotEqualTo("PUBLISHED");
        assertThat(chartPublishedVersionService.getPublishedVersion()).isEqualTo("v20260314100000000");

        verify(valueOperations, never()).set(anyString(), anyString());
        verify(chartElasticsearchIndexService, never()).publishCandidateAlias(anyString());
    }

    @Test
    @DisplayName("Redis 쓰기 실패가 나도 authoritative state 기준 published version과 lastUpdated는 복구 가능하다")
    void redisWriteFailure_doesNotBreakPublishedStateFallback() {
        final LocalDateTime logicalAsOfAt = LocalDateTime.of(2026, 3, 14, 18, 0);
        final String version = "v20260314180000000";

        chartScoreQueryRepository.preparePublishStageTable();
        chartScoreQueryRepository.bulkUpsertPublishStageChartScores(
                Collections.singletonList(sampleScore(9001L))
        );
        chartPublishStateService.startCandidateGeneration(
                version,
                logicalAsOfAt,
                chartPublishProperties.getStageTableName(),
                "chart_scores_candidate_" + version
        );

        given(redisTemplate.keys("chart:v1:*")).willReturn(Collections.emptySet());
        given(valueOperations.get(anyString())).willThrow(new RuntimeException("redis read failure"));
        org.mockito.Mockito.doThrow(new RuntimeException("redis write failure"))
                .when(valueOperations).set(anyString(), anyString());

        chartPublishOrchestratorService.publishVersion(version);

        final ChartPublishState state = chartPublishStateService.requireState();

        assertThat(state.getCurrentVersion()).isEqualTo(version);
        assertThat(state.getLogicalAsOfAt()).isEqualTo(logicalAsOfAt);
        assertThat(chartPublishedVersionService.getPublishedVersion()).isEqualTo(version);
        assertThat(chartLastUpdatedService.getLastUpdated()).isEqualTo(logicalAsOfAt);
        assertThat(countRows("chart_scores")).isEqualTo(1L);
    }

    private void publishBaselineVersion(final String version, final LocalDateTime logicalAsOfAt) {
        chartScoreQueryRepository.preparePublishStageTable();
        chartScoreQueryRepository.bulkUpsertPublishStageChartScores(
                Collections.singletonList(sampleScore(1001L))
        );
        chartPublishStateService.startCandidateGeneration(
                version,
                logicalAsOfAt,
                chartPublishProperties.getStageTableName(),
                "chart_scores_candidate_" + version
        );
        chartPublishStateService.markPublishing(version);
        chartScoreQueryRepository.publishStageTable();
        chartPublishStateService.markPublished(version);
    }

    private long countRows(final String tableName) {
        final Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
        return Optional.ofNullable(count).orElse(0L);
    }

    private ChartScoreDto sampleScore(final long releaseId) {
        return new ChartScoreDto(
                releaseId,
                4.2,
                4.1,
                123.0,
                456L,
                false,
                "[{\"id\":1,\"isPrimary\":true}]",
                ReleaseType.ALBUM,
                2024,
                "[10,11]",
                82L,
                "[\"ENGLISH\"]"
        );
    }
}
