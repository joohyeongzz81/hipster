package com.hipster.chart.publish.service;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.hipster.batch.chart.dto.ChartScoreDto;
import com.hipster.batch.chart.repository.ChartScoreQueryRepository;
import com.hipster.chart.config.ChartPublishProperties;
import com.hipster.chart.domain.ChartDocument;
import com.hipster.chart.publish.domain.ChartPublishState;
import com.hipster.chart.service.ChartCacheKeyGenerator;
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
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.batch.jdbc.initialize-schema=always",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "chart.publish.chart-name=weekly_chart",
        "chart.search.index-name=chart_scores_publish_e2e_it"
})
@Testcontainers
@ActiveProfiles("test")
class ChartPublishEndToEndIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("hipster_chart_publish_e2e_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static ElasticsearchContainer elasticsearch = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.11.4")
    )
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.elasticsearch.uris", () -> "http://" + elasticsearch.getHttpHostAddress());
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    @Qualifier("chartUpdateJob")
    private Job chartUpdateJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

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
    private ChartCacheKeyGenerator chartCacheKeyGenerator;

    @Autowired
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @MockBean
    private RatingSummaryConsumer ratingSummaryConsumer;

    @MockBean
    private UserActivityConsumer userActivityConsumer;

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

        redisTemplate.execute((RedisCallback<String>) connection -> {
            connection.serverCommands().flushDb();
            return "OK";
        });

        deleteAliasIfExists(chartPublishProperties.resolveAliasName("chart_scores_publish_e2e_it"));
        deleteIndexIfExists(chartElasticsearchIndexService.buildCandidateIndexName("v20260314111111000"));
        deleteIndexIfExists(chartElasticsearchIndexService.buildCandidateIndexName("v20260314222222000"));
    }

    @Test
    @DisplayName("MySQL + ES + Redis 전체가 붙은 publish path end-to-end에서 세 저장소가 같은 published version을 가리킨다")
    void chartUpdateJob_publishPathKeepsMysqlEsRedisInSync() throws Exception {
        final String baselineVersion = "v20260314111111000";
        final LocalDateTime baselineLogicalAsOfAt = LocalDateTime.of(2026, 3, 14, 11, 11, 11);
        publishBaselineVersion(baselineVersion, baselineLogicalAsOfAt, sampleScore(1001L, 4.1));

        redisTemplate.opsForValue().set("chart:v1:v20260314101010000:all:page:0", "{\"stale\":true}");
        seedReleaseAndSummary(6006L, LocalDateTime.of(2026, 3, 14, 22, 22, 22));

        final JobExecution execution = jobLauncher.run(
                chartUpdateJob,
                new JobParametersBuilder()
                        .addLong("ts", System.currentTimeMillis())
                        .toJobParameters()
        );

        final ChartPublishState state = chartPublishStateService.requireState();
        final Set<String> stepNames = execution.getStepExecutions().stream()
                .map(StepExecution::getStepName)
                .collect(Collectors.toSet());
        final Map<String, Long> stepDurations = execution.getStepExecutions().stream()
                .collect(Collectors.toMap(
                        StepExecution::getStepName,
                        step -> Duration.between(step.getStartTime(), step.getEndTime()).toMillis()
                ));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepNames).containsExactlyInAnyOrder(
                "globalAvgCalculationStep",
                "prepareCandidateVersionStep",
                "chartScoreUpdateStep",
                "elasticsearchSyncStep",
                "cacheEvictionStep"
        );

        assertThat(state.getCurrentVersion()).isNotBlank();
        assertThat(state.getCurrentVersion()).isNotEqualTo(baselineVersion);
        assertThat(state.getPreviousVersion()).isEqualTo(baselineVersion);
        assertThat(state.getLogicalAsOfAt()).isEqualTo(LocalDateTime.of(2026, 3, 14, 22, 22, 22));

        assertThat(countRows("chart_scores")).isEqualTo(1L);
        assertThat(findSingleReleaseId("chart_scores")).isEqualTo(6006L);

        final String aliasTarget = resolveAliasTarget();
        final String expectedIndex = chartElasticsearchIndexService.buildCandidateIndexName(state.getCurrentVersion());
        assertThat(aliasTarget).isEqualTo(expectedIndex);
        assertThat(searchPublishedReleaseIds()).containsExactly(6006L);

        assertThat(redisTemplate.opsForValue().get(chartPublishProperties.getPublishedVersionCacheKey()))
                .isEqualTo(state.getCurrentVersion());
        assertThat(redisTemplate.opsForValue().get(ChartLastUpdatedService.LAST_UPDATED_KEY))
                .isEqualTo(state.getLogicalAsOfAt().toString());
        assertThat(redisTemplate.opsForValue().get("chart:v1:v20260314101010000:all:page:0")).isNull();

        assertThat(chartPublishedVersionService.getPublishedVersion()).isEqualTo(state.getCurrentVersion());
        assertThat(chartLastUpdatedService.getLastUpdated()).isEqualTo(state.getLogicalAsOfAt());
        assertThat(chartCacheKeyGenerator.generateKey(null, 0))
                .isEqualTo("chart:v1:" + state.getCurrentVersion() + ":all:page:0");

        assertThat(stepDurations).containsKeys(
                "prepareCandidateVersionStep",
                "chartScoreUpdateStep",
                "elasticsearchSyncStep",
                "cacheEvictionStep"
        );
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
                "End To End Publish Album",
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
                654L,
                4.6d,
                777.7d,
                168.0d,
                java.sql.Timestamp.valueOf(logicalAsOfAt),
                java.sql.Timestamp.valueOf(logicalAsOfAt)
        );
    }

    private void publishBaselineVersion(final String version,
                                        final LocalDateTime logicalAsOfAt,
                                        final ChartScoreDto scoreDto) {
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

    private String resolveAliasTarget() throws IOException {
        final String aliasName = chartPublishProperties.resolveAliasName("chart_scores_publish_e2e_it");
        final var response = elasticsearchClient.indices().getAlias(req -> req.name(aliasName));
        return response.result().keySet().stream().findFirst().orElse(null);
    }

    private List<Long> searchPublishedReleaseIds() {
        return elasticsearchOperations.search(
                        NativeQuery.builder()
                                .withQuery(Query.of(q -> q.matchAll(m -> m)))
                                .build(),
                        ChartDocument.class,
                        IndexCoordinates.of(chartElasticsearchIndexService.resolvePublishedIndexName())
                ).getSearchHits().stream()
                .map(SearchHit::getContent)
                .map(ChartDocument::getReleaseId)
                .toList();
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

    private void deleteIndexIfExists(final String indexName) throws IOException {
        final boolean exists = elasticsearchClient.indices().exists(req -> req.index(indexName)).value();
        if (exists) {
            elasticsearchClient.indices().delete(req -> req.index(indexName));
        }
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
