package com.hipster.chart.publish.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.hipster.batch.chart.dto.ChartScoreDto;
import com.hipster.batch.chart.repository.ChartScoreQueryRepository;
import com.hipster.chart.config.ChartPublishProperties;
import com.hipster.chart.publish.domain.ChartPublishState;
import com.hipster.chart.service.ChartElasticsearchIndexService;
import com.hipster.chart.service.ChartLastUpdatedService;
import com.hipster.rating.event.RatingSummaryConsumer;
import com.hipster.release.domain.ReleaseType;
import com.hipster.user.event.UserActivityConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.batch.jdbc.initialize-schema=always",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "chart.publish.chart-name=weekly_chart",
        "chart.search.index-name=chart_scores_publish_it"
})
@Testcontainers
@ActiveProfiles("test")
class ChartPublishAliasRollbackIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("hipster_chart_publish_alias_test")
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
    private ChartElasticsearchIndexService chartElasticsearchIndexService;

    @Autowired
    private ChartLastUpdatedService chartLastUpdatedService;

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
        jdbcTemplate.execute("DELETE FROM chart_publish_history");
        jdbcTemplate.execute("DELETE FROM chart_publish_state");
        jdbcTemplate.execute("DROP TABLE IF EXISTS chart_scores_failed");
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + chartPublishProperties.getPreviousTableName());
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + chartPublishProperties.getStageTableName());
        jdbcTemplate.execute("DELETE FROM chart_scores");

        valueOperations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(redisTemplate.keys("chart:v1:*")).willReturn(Collections.emptySet());

        deleteAliasIfExists(chartPublishProperties.resolveAliasName("chart_scores_publish_it"));
        deleteIndexIfExists(chartElasticsearchIndexService.buildCandidateIndexName("v20260314101010000"));
        deleteIndexIfExists(chartElasticsearchIndexService.buildCandidateIndexName("v20260314151515000"));
    }

    @Test
    @DisplayName("real ES alias publish 이후 rollback 하면 previous version/index/logical_as_of_at으로 복원된다")
    void publishAndRollback_restoresPreviousVersionAndAlias() throws Exception {
        final String oldVersion = "v20260314101010000";
        final LocalDateTime oldLogicalAsOfAt = LocalDateTime.of(2026, 3, 14, 10, 10, 10);
        prepareCandidate(oldVersion, oldLogicalAsOfAt, sampleScore(1001L, 4.1));

        final var oldValidation = chartPublishOrchestratorService.validateCandidateVersion(oldVersion);
        assertThat(oldValidation.blockingPassed()).isTrue();
        chartPublishOrchestratorService.publishVersion(oldVersion);

        ChartPublishState publishedOld = chartPublishStateService.requireState();
        assertThat(publishedOld.getCurrentVersion()).isEqualTo(oldVersion);
        assertThat(resolveAliasTarget()).isEqualTo(chartElasticsearchIndexService.buildCandidateIndexName(oldVersion));
        assertThat(countRows("chart_scores")).isEqualTo(1L);
        assertThat(findSingleReleaseId("chart_scores")).isEqualTo(1001L);

        final String newVersion = "v20260314151515000";
        final LocalDateTime newLogicalAsOfAt = LocalDateTime.of(2026, 3, 14, 15, 15, 15);
        prepareCandidate(newVersion, newLogicalAsOfAt, sampleScore(2002L, 4.8));

        final var newValidation = chartPublishOrchestratorService.validateCandidateVersion(newVersion);
        assertThat(newValidation.blockingPassed()).isTrue();
        chartPublishOrchestratorService.publishVersion(newVersion);

        ChartPublishState publishedNew = chartPublishStateService.requireState();
        assertThat(publishedNew.getCurrentVersion()).isEqualTo(newVersion);
        assertThat(publishedNew.getPreviousVersion()).isEqualTo(oldVersion);
        assertThat(resolveAliasTarget()).isEqualTo(chartElasticsearchIndexService.buildCandidateIndexName(newVersion));
        assertThat(findSingleReleaseId("chart_scores")).isEqualTo(2002L);
        assertThat(chartLastUpdatedService.getLastUpdated()).isEqualTo(newLogicalAsOfAt);

        chartPublishOrchestratorService.rollbackToPreviousVersion("TEST_ROLLBACK");

        ChartPublishState rolledBack = chartPublishStateService.requireState();
        assertThat(rolledBack.getCurrentVersion()).isEqualTo(oldVersion);
        assertThat(rolledBack.getLogicalAsOfAt()).isEqualTo(oldLogicalAsOfAt);
        assertThat(resolveAliasTarget()).isEqualTo(chartElasticsearchIndexService.buildCandidateIndexName(oldVersion));
        assertThat(countRows("chart_scores")).isEqualTo(1L);
        assertThat(findSingleReleaseId("chart_scores")).isEqualTo(1001L);
        assertThat(chartLastUpdatedService.getLastUpdated()).isEqualTo(oldLogicalAsOfAt);
    }

    private void prepareCandidate(final String version,
                                  final LocalDateTime logicalAsOfAt,
                                  final ChartScoreDto scoreDto) {
        chartScoreQueryRepository.preparePublishStageTable();
        chartScoreQueryRepository.bulkUpsertPublishStageChartScores(Collections.singletonList(scoreDto));
        chartPublishStateService.startCandidateGeneration(
                version,
                logicalAsOfAt,
                chartPublishProperties.getStageTableName(),
                chartElasticsearchIndexService.buildCandidateIndexName(version)
        );
        chartElasticsearchIndexService.rebuildCandidateIndex(version, 100);
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

    private String resolveAliasTarget() throws IOException {
        final String aliasName = chartPublishProperties.resolveAliasName("chart_scores_publish_it");
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
