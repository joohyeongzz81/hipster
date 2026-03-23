package com.hipster.chart.publish.service;

import com.hipster.batch.chart.dto.ChartScoreDto;
import com.hipster.batch.chart.repository.ChartScoreQueryRepository;
import com.hipster.chart.config.ChartPublishProperties;
import com.hipster.chart.dto.request.ChartFilterRequest;
import com.hipster.chart.service.ChartCacheKeyGenerator;
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
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;

@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.batch.jdbc.initialize-schema=always",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "chart.publish.chart-name=weekly_chart",
        "chart.search.index-name=chart_scores_publish_redis_it"
})
@Testcontainers
@ActiveProfiles("test")
class ChartPublishRedisIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("hipster_chart_publish_redis_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

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
    private ChartCacheKeyGenerator chartCacheKeyGenerator;

    @MockBean
    private ChartElasticsearchIndexService chartElasticsearchIndexService;

    @MockBean
    private RatingSummaryConsumer ratingSummaryConsumer;

    @MockBean
    private UserActivityConsumer userActivityConsumer;

    @BeforeEach
    void setUp() {
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

        given(chartElasticsearchIndexService.buildCandidateIndexName(anyString()))
                .willAnswer(invocation -> "chart_scores_candidate_" + invocation.getArgument(0));
        given(chartElasticsearchIndexService.countDocumentsByVersion(anyString())).willReturn(1L);
        given(chartElasticsearchIndexService.isIndexSearchableByVersion(anyString())).willReturn(true);
        doNothing().when(chartElasticsearchIndexService).rebuildCandidateIndex(anyString(), anyInt());
        doNothing().when(chartElasticsearchIndexService).publishCandidateAlias(anyString());
    }

    @Test
    @DisplayName("publish 성공 시 실제 Redis에 published version과 logical_as_of metadata가 저장되고 기존 chart cache는 비워진다")
    void publishWritesRedisKeysAndEvictsLegacyCache() {
        final String version = "v20260314202020000";
        final LocalDateTime logicalAsOfAt = LocalDateTime.of(2026, 3, 14, 20, 20, 20);

        redisTemplate.opsForValue().set("chart:v1:v20260314191919000:all:page:0", "{\"stale\":true}");

        prepareCandidate(version, logicalAsOfAt, sampleScore(3003L, 4.5));
        final var validation = chartPublishOrchestratorService.validateCandidateVersion(version);

        assertThat(validation.blockingPassed()).isTrue();

        chartPublishOrchestratorService.publishVersion(version);

        assertThat(redisTemplate.opsForValue().get(chartPublishProperties.getPublishedVersionCacheKey()))
                .isEqualTo(version);
        assertThat(redisTemplate.opsForValue().get(ChartLastUpdatedService.LAST_UPDATED_KEY))
                .isEqualTo(logicalAsOfAt.toString());
        assertThat(redisTemplate.opsForValue().get("chart:v1:v20260314191919000:all:page:0")).isNull();

        assertThat(chartPublishedVersionService.getPublishedVersion()).isEqualTo(version);
        assertThat(chartLastUpdatedService.getLastUpdated()).isEqualTo(logicalAsOfAt);
        assertThat(chartCacheKeyGenerator.generateKey((ChartFilterRequest) null, 0))
                .isEqualTo("chart:v1:" + version + ":all:page:0");
    }

    @Test
    @DisplayName("Redis 키가 유실돼도 published version과 lastUpdated는 authoritative state에서 복원된다")
    void missingRedisKeys_fallBackToAuthoritativeState() {
        final String version = "v20260314212121000";
        final LocalDateTime logicalAsOfAt = LocalDateTime.of(2026, 3, 14, 21, 21, 21);

        prepareCandidate(version, logicalAsOfAt, sampleScore(4004L, 4.7));
        chartPublishOrchestratorService.publishVersion(version);

        redisTemplate.delete(chartPublishProperties.getPublishedVersionCacheKey());
        redisTemplate.delete(ChartLastUpdatedService.LAST_UPDATED_KEY);

        assertThat(chartPublishStateService.requireState().getCurrentVersion()).isEqualTo(version);
        assertThat(chartPublishedVersionService.getPublishedVersion()).isEqualTo(version);
        assertThat(chartLastUpdatedService.getLastUpdated()).isEqualTo(logicalAsOfAt);
        assertThat(redisTemplate.opsForValue().get(ChartLastUpdatedService.LAST_UPDATED_KEY))
                .isEqualTo(logicalAsOfAt.toString());
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
