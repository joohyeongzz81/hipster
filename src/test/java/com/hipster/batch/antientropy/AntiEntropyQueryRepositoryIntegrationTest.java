package com.hipster.batch.antientropy;

import com.hipster.global.config.QuerydslConfig;
import com.hipster.rating.domain.Rating;
import com.hipster.rating.domain.ReleaseRatingSummary;
import com.hipster.rating.repository.RatingRepository;
import com.hipster.rating.repository.ReleaseRatingSummaryRepository;
import com.hipster.release.domain.Release;
import com.hipster.release.domain.ReleaseType;
import com.hipster.release.repository.ReleaseRepository;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.batch.jdbc.initialize-schema=never",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "hipster.datasource.routing-enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AntiEntropyQueryRepository.class, QuerydslConfig.class})
class AntiEntropyQueryRepositoryIntegrationTest {

    private static final String DEFAULT_TEST_JDBC_URL =
            "jdbc:mysql://127.0.0.1:3306/hipster_rating_antientropy_test?createDatabaseIfNotExist=true&rewriteBatchedStatements=true&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String DEFAULT_TEST_USERNAME = "root";
    private static final String DEFAULT_TEST_PASSWORD = "password";

    @DynamicPropertySource
    static void configureProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredProperty("rating.antientropy.test.jdbc-url"));
        registry.add("spring.datasource.username", () -> requiredProperty("rating.antientropy.test.username"));
        registry.add("spring.datasource.password", () -> requiredProperty("rating.antientropy.test.password"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.master.jdbc-url", () -> requiredProperty("rating.antientropy.test.jdbc-url"));
        registry.add("spring.datasource.master.username", () -> requiredProperty("rating.antientropy.test.username"));
        registry.add("spring.datasource.master.password", () -> requiredProperty("rating.antientropy.test.password"));
        registry.add("spring.datasource.master.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.slave.jdbc-url", () -> requiredProperty("rating.antientropy.test.jdbc-url"));
        registry.add("spring.datasource.slave.username", () -> requiredProperty("rating.antientropy.test.username"));
        registry.add("spring.datasource.slave.password", () -> requiredProperty("rating.antientropy.test.password"));
        registry.add("spring.datasource.slave.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired
    private AntiEntropyQueryRepository antiEntropyQueryRepository;

    @Autowired
    private RatingRepository ratingRepository;

    @Autowired
    private ReleaseRatingSummaryRepository summaryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReleaseRepository releaseRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long liveReleaseId;
    private Long orphanReleaseId;

    @BeforeEach
    void setUp() {
        summaryRepository.deleteAllInBatch();
        ratingRepository.deleteAllInBatch();
        releaseRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        liveReleaseId = releaseRepository.save(Release.builder()
                .artistId(1L)
                .title("live-release")
                .releaseType(ReleaseType.ALBUM)
                .releaseDate(LocalDate.of(2026, 3, 19))
                .build()).getId();

        orphanReleaseId = releaseRepository.save(Release.builder()
                .artistId(2L)
                .title("orphan-release")
                .releaseType(ReleaseType.ALBUM)
                .releaseDate(LocalDate.of(2026, 3, 19))
                .build()).getId();

        final User user = userRepository.save(User.builder()
                .username("anti-entropy-user")
                .email("anti-entropy@test.com")
                .passwordHash("hash")
                .build());
        user.updateWeightingScore(0.8);
        userRepository.saveAndFlush(user);

        ratingRepository.saveAndFlush(Rating.builder()
                .userId(user.getId())
                .releaseId(liveReleaseId)
                .score(4.0)
                .build());

        insertSummaryRow(liveReleaseId, 9L, 1.5, new BigDecimal("2.0000"), new BigDecimal("1.0000"));
        insertSummaryRow(orphanReleaseId, 1L, 5.0, new BigDecimal("5.0000"), new BigDecimal("1.0000"));
    }

    @Test
    @DisplayName("anti-entropy 는 ratings 와 기존 summary 양쪽 release 를 수집한다")
    void findAllReleaseIds_IncludesRatingsAndSummaryCandidates() {
        final List<Long> releaseIds = antiEntropyQueryRepository.findAllReleaseIds();

        assertThat(releaseIds).contains(liveReleaseId, orphanReleaseId);
    }

    @Test
    @DisplayName("anti-entropy 는 orphan summary 를 삭제하고 살아있는 release summary 를 source-of-truth 기준으로 재계산한다")
    void reconcileChunk_DeletesOrphanSummaryAndRebuildsLiveSummary() {
        final LocalDateTime batchSyncedAt = LocalDateTime.of(2026, 3, 19, 12, 0, 0);

        antiEntropyQueryRepository.reconcileChunk(List.of(liveReleaseId, orphanReleaseId), batchSyncedAt);

        final ReleaseRatingSummary rebuiltSummary = summaryRepository.findByReleaseId(liveReleaseId).orElseThrow();
        assertThat(rebuiltSummary.getTotalRatingCount()).isEqualTo(1L);
        assertThat(rebuiltSummary.getAverageScore()).isEqualTo(4.0);
        assertThat(rebuiltSummary.getWeightedScoreSum()).isEqualByComparingTo("3.2000");
        assertThat(rebuiltSummary.getWeightedCountSum()).isEqualByComparingTo("0.8000");
        assertThat(rebuiltSummary.getBatchSyncedAt()).isEqualTo(batchSyncedAt);

        assertThat(summaryRepository.findByReleaseId(orphanReleaseId)).isEmpty();
    }

    private void insertSummaryRow(
            final Long releaseId,
            final long totalRatingCount,
            final double averageScore,
            final BigDecimal weightedScoreSum,
            final BigDecimal weightedCountSum
    ) {
        final LocalDateTime now = LocalDateTime.of(2026, 3, 19, 0, 0, 0);
        jdbcTemplate.update(
                """
                INSERT INTO release_rating_summary
                    (release_id, total_rating_count, average_score, weighted_score_sum, weighted_count_sum, batch_synced_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                releaseId,
                totalRatingCount,
                averageScore,
                weightedScoreSum,
                weightedCountSum,
                now,
                now
        );
    }

    private static String requiredProperty(final String key) {
        final String value = System.getProperty(key);
        if (value != null && !value.isBlank()) {
            return value;
        }

        final String envKey = key.toUpperCase().replace('.', '_').replace('-', '_');
        final String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }

        return switch (key) {
            case "rating.antientropy.test.jdbc-url" -> DEFAULT_TEST_JDBC_URL;
            case "rating.antientropy.test.username" -> DEFAULT_TEST_USERNAME;
            case "rating.antientropy.test.password" -> DEFAULT_TEST_PASSWORD;
            default -> throw new IllegalStateException("Missing required property or env: " + key + " / " + envKey);
        };
    }
}
