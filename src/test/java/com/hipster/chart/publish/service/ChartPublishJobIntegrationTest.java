package com.hipster.chart.publish.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.hipster.batch.chart.dto.ChartScoreDto;
import com.hipster.batch.chart.repository.ChartScoreQueryRepository;
import com.hipster.chart.config.ChartPublishProperties;
import com.hipster.chart.publish.domain.ChartPublishState;
import com.hipster.chart.publish.domain.ChartPublishStatus;
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
import org.springframework.batch.core.StepExecution;
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
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.batch.jdbc.initialize-schema=always",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "chart.publish.enabled=true",
        "chart.publish.chart-name=weekly_chart",
        "chart.search.index-name=chart_scores_publish_job_it"
})
@Testcontainers
@ActiveProfiles("test")
class ChartPublishJobIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("hipster_chart_publish_job_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static ElasticsearchContainer elasticsearch = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.11.4")
    )
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node");

    @DynamicPropertySource
    static void configureProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.elasticsearch.uris", () -> "http://" + elasticsearch.getHttpHostAddress());
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

    @Autowired
    private ChartElasticsearchIndexService chartElasticsearchIndexService;

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private RatingSummaryConsumer ratingSummaryConsumer;

    @MockBean
    private UserActivityConsumer userActivityConsumer;

    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("DELETE FROM release_languages");
        jdbcTemplate.execute("DELETE FROM release_descriptors");
        jdbcTemplate.execute("DELETE FROM release_genres");
        jdbcTemplate.execute("DELETE FROM release_rating_summary");
        jdbcTemplate.execute("DELETE FROM releases");
        jdbcTemplate.execute("DELETE FROM chart_publish_history");
        jdbcTemplate.execute("DELETE FROM chart_publish_state");
        jdbcTemplate.execute("DROP TABLE IF EXISTS chart_scores_failed");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + chartPublishProperties.getPreviousTableName());
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + chartPublishProperties.getStageTableName());
        jdbcTemplate.execute("DELETE FROM chart_scores");

        valueOperations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(redisTemplate.keys("chart:v1:*")).willReturn(Collections.emptySet());
        given(valueOperations.get(anyString())).willReturn(null);

        deleteAliasIfExists(chartPublishProperties.resolveAliasName("chart_scores_publish_job_it"));
    }

    @Test
    @DisplayName("publish enabled chartUpdateJob는 stage->candidate ES->publish를 거쳐 previous/current version을 갱신한다")
    void chartUpdateJob_publishPathCompletesAndPromotesNewVersion() throws Exception {
        final String baselineVersion = "v20260314120000000";
        final LocalDateTime baselineLogicalAsOfAt = LocalDateTime.of(2026, 3, 14, 12, 0);
        publishBaselineVersion(baselineVersion, baselineLogicalAsOfAt, sampleScore(1001L, 4.1));

        seedReleaseAndSummary(2002L, LocalDateTime.of(2026, 3, 14, 18, 0));

        final JobExecution execution = jobLauncher.run(
                chartUpdateJob,
                new JobParametersBuilder()
                        .addLong("ts", System.currentTimeMillis())
                        .toJobParameters()
        );

        final ChartPublishState state = chartPublishStateService.requireState();
        final Set<String> stepNames = execution.getStepExecutions().stream()
                .map(StepExecution::getStepName)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepNames).containsExactlyInAnyOrder(
                "globalAvgCalculationStep",
                "prepareCandidateVersionStep",
                "chartScoreUpdateStep",
                "elasticsearchSyncStep",
                "cacheEvictionStep"
        );

        assertThat(state.getStatus()).isEqualTo(ChartPublishStatus.PUBLISHED);
        assertThat(state.getCurrentVersion()).isNotBlank();
        assertThat(state.getCurrentVersion()).isNotEqualTo(baselineVersion);
        assertThat(state.getPreviousVersion()).isEqualTo(baselineVersion);
        assertThat(state.getLogicalAsOfAt()).isEqualTo(LocalDateTime.of(2026, 3, 14, 18, 0));
        assertThat(state.getLastValidationStatus().name()).isEqualTo("PASSED");

        assertThat(countRows("chart_scores")).isEqualTo(1L);
        assertThat(findSingleReleaseId("chart_scores")).isEqualTo(2002L);
        assertThat(resolveAliasTarget()).isEqualTo(chartElasticsearchIndexService.buildCandidateIndexName(state.getCurrentVersion()));
        assertThat(chartPublishedVersionService.getPublishedVersionOrLegacy()).isEqualTo(state.getCurrentVersion());
        assertThat(chartLastUpdatedService.getLastUpdated()).isEqualTo(LocalDateTime.of(2026, 3, 14, 18, 0));

        verify(valueOperations, atLeastOnce()).set(chartPublishProperties.getPublishedVersionCacheKey(), state.getCurrentVersion());
        verify(valueOperations, atLeastOnce()).set(ChartLastUpdatedService.LAST_UPDATED_KEY, state.getLogicalAsOfAt().toString());
    }

    private void seedReleaseAndSummary(final long releaseId, final LocalDateTime logicalAsOfAt) {
        jdbcTemplate.update("""
                INSERT INTO releases (
                    id, artist_id, location_id, title, release_type, release_date, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """,
                releaseId,
                1L,
                82L,
                "Publish Job Test Album",
                "ALBUM",
                java.sql.Date.valueOf("2024-01-01"),
                "ACTIVE"
        );

        jdbcTemplate.update("""
                INSERT INTO release_rating_summary (
                    id, release_id, total_rating_count, average_score, weighted_score_sum, weighted_count_sum, batch_synced_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                releaseId,
                releaseId,
                456L,
                4.2d,
                516.6d,
                123.0d,
                java.sql.Timestamp.valueOf(logicalAsOfAt),
                java.sql.Timestamp.valueOf(logicalAsOfAt)
        );
    }

    private void publishBaselineVersion(final String version,
                                        final LocalDateTime logicalAsOfAt,
                                        final ChartScoreDto scoreDto) throws Exception {
        chartScoreQueryRepository.preparePublishStageTable();
        chartScoreQueryRepository.bulkUpsertPublishStageChartScores(List.of(scoreDto));
        chartPublishStateService.startCandidateGeneration(
                version,
                logicalAsOfAt,
                chartPublishProperties.getStageTableName(),
                chartElasticsearchIndexService.buildCandidateIndexName(version)
        );
        chartElasticsearchIndexService.rebuildCandidateIndex(version, 100);
        final var validation = chartPublishOrchestratorService.validateCandidateVersion(version);
        assertThat(validation.blockingPassed()).isTrue();
        chartPublishOrchestratorService.publishVersion(version);
    }

    private void deleteAliasIfExists(final String aliasName) {
        try {
            final var response = elasticsearchClient.indices().getAlias(req -> req.name(aliasName));
            for (String indexName : response.result().keySet()) {
                elasticsearchClient.indices().updateAliases(builder ->
                        builder.actions(action -> action.remove(remove -> remove.index(indexName).alias(aliasName)))
                );
            }
        } catch (Exception ignored) {
        }
    }

    private String resolveAliasTarget() throws IOException {
        final String aliasName = chartPublishProperties.resolveAliasName("chart_scores_publish_job_it");
        final var response = elasticsearchClient.indices().getAlias(req -> req.name(aliasName));
        return response.result().keySet().stream().findFirst().orElse(null);
    }

    private long countRows(final String tableName) {
        final Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
        return Optional.ofNullable(count).orElse(0L);
    }

    private Long findSingleReleaseId(final String tableName) {
        return jdbcTemplate.queryForObject("SELECT release_id FROM " + tableName + " LIMIT 1", Long.class);
    }

    private ChartScoreDto sampleScore(final long releaseId, final double score) {
        return new ChartScoreDto(
                releaseId,
                score,
                score - 0.1,
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
