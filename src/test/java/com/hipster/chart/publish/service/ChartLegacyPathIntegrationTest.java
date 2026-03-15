package com.hipster.chart.publish.service;

import com.hipster.chart.service.ChartCacheKeyGenerator;
import com.hipster.chart.service.ChartElasticsearchIndexService;
import com.hipster.chart.service.ChartLastUpdatedService;
import com.hipster.rating.event.RatingSummaryConsumer;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.BDDMockito.given;

@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.batch.jdbc.initialize-schema=always",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "chart.publish.enabled=false"
})
@Testcontainers
@ActiveProfiles("test")
class ChartLegacyPathIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("hipster_chart_legacy_path_test")
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
    private ChartPublishedVersionService chartPublishedVersionService;

    @Autowired
    private ChartCacheKeyGenerator chartCacheKeyGenerator;

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
        jdbcTemplate.execute("DELETE FROM release_languages");
        jdbcTemplate.execute("DELETE FROM release_descriptors");
        jdbcTemplate.execute("DELETE FROM release_genres");
        jdbcTemplate.execute("DELETE FROM release_rating_summary");
        jdbcTemplate.execute("DELETE FROM releases");
        jdbcTemplate.execute("DELETE FROM chart_publish_history");
        jdbcTemplate.execute("DELETE FROM chart_publish_state");
        jdbcTemplate.execute("DELETE FROM chart_scores");

        valueOperations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(redisTemplate.keys("chart:v1:*")).willReturn(Set.of("chart:v1:all:page:0"));
    }

    @Test
    @DisplayName("chart.publish.enabled=false 이면 기존 legacy batch 경로만 실행되고 publish state는 건드리지 않는다")
    void legacyPathRunsWithoutPublishState() throws Exception {
        seedReleaseAndSummary(5005L, LocalDateTime.of(2026, 3, 14, 22, 0));

        final JobExecution execution = jobLauncher.run(
                chartUpdateJob,
                new JobParametersBuilder()
                        .addLong("ts", System.currentTimeMillis())
                        .toJobParameters()
        );

        final Set<String> stepNames = execution.getStepExecutions().stream()
                .map(StepExecution::getStepName)
                .collect(Collectors.toSet());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepNames).containsExactlyInAnyOrder(
                "globalAvgCalculationStep",
                "chartScoreUpdateStep",
                "elasticsearchSyncStep",
                "cacheEvictionStep"
        );
        assertThat(stepNames).doesNotContain("prepareCandidateVersionStep");
        assertThat(countRows("chart_scores")).isEqualTo(1L);
        assertThat(findSingleReleaseId("chart_scores")).isEqualTo(5005L);
        assertThat(countRows("chart_publish_state")).isZero();
        assertThat(chartPublishedVersionService.getPublishedVersionOrLegacy()).isEqualTo("legacy");
        assertThat(chartCacheKeyGenerator.generateKey(null, 0)).isEqualTo("chart:v1:all:page:0");

        verify(chartElasticsearchIndexService).rebuildIndex();
        verify(chartElasticsearchIndexService, never()).rebuildCandidateIndex(anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(redisTemplate).delete(Set.of("chart:v1:all:page:0"));
        verify(valueOperations, atLeastOnce()).set(org.mockito.ArgumentMatchers.eq(ChartLastUpdatedService.LAST_UPDATED_KEY), anyString());
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
                "Legacy Path Test Album",
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
                321L,
                4.0d,
                444.4d,
                111.0d,
                java.sql.Timestamp.valueOf(logicalAsOfAt),
                java.sql.Timestamp.valueOf(logicalAsOfAt)
        );
    }

    private long countRows(final String tableName) {
        final Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tableName, Long.class);
        return count == null ? 0L : count;
    }

    private Long findSingleReleaseId(final String tableName) {
        return jdbcTemplate.queryForObject("SELECT release_id FROM " + tableName + " LIMIT 1", Long.class);
    }
}
