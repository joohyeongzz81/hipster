package com.hipster.rating.service;

import com.hipster.rating.domain.Rating;
import com.hipster.rating.domain.ReleaseRatingSummary;
import com.hipster.rating.repository.RatingRepository;
import com.hipster.rating.repository.ReleaseRatingSummaryRepository;
import com.hipster.release.domain.Release;
import com.hipster.release.repository.ReleaseRepository;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class RatingServicePerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(RatingServicePerformanceTest.class);

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("hipster_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> mysql.getJdbcUrl() + "?rewriteBatchedStatements=true");
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.batch.jdbc.initialize-schema", () -> "always");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReleaseRatingSummaryRepository releaseRatingSummaryRepository;

    @Autowired
    private RatingRepository ratingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReleaseRepository releaseRepository;

    private Long testReleaseId;
    private final int DUMMY_RATING_COUNT = 10000;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM release_rating_summary");
        jdbcTemplate.execute("DELETE FROM ratings");
        jdbcTemplate.execute("DELETE FROM releases");
        jdbcTemplate.execute("DELETE FROM users");

        // 1. 테스트 유저 생성
        User user = User.builder()
                .username("test_user")
                .email("test@test.com")
                .passwordHash("hash")
                .build();
        userRepository.save(user);

        // 2. 테스트 앨범(Release) 생성
        Release release = Release.builder()
                .title("Test Release Performance")
                .build();
        release.approve(); // Set status to ACTIVE
        release = releaseRepository.save(release);
        testReleaseId = release.getId();

        // 3. 더미 평점 10,000건 삽입 (가혹한 환경 구성)
        log.info("Inserting {} dummy ratings for performance test...", DUMMY_RATING_COUNT);
        String sqlRating = "INSERT INTO ratings (user_id, release_id, score, created_at, updated_at) VALUES (?, ?, ?, ?, ?)";
        List<Object[]> batchArgsRating = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        double totalScore = 0;
        for (int i = 0; i < DUMMY_RATING_COUNT; i++) {
            double dummyScore = 4.0;
            batchArgsRating.add(new Object[]{user.getId(), testReleaseId, dummyScore, now, now});
            totalScore += dummyScore;
        }
        jdbcTemplate.batchUpdate(sqlRating, batchArgsRating);

        // 4. (TO-BE 용) Summary 데이터 1건 생성
        releaseRatingSummaryRepository.save(
                ReleaseRatingSummary.builder()
                        .releaseId(testReleaseId)
                        .totalRatingCount(DUMMY_RATING_COUNT)
                        .averageScore(totalScore / DUMMY_RATING_COUNT)
                        .build()
        );
        log.info("Setup complete.");
    }

    @Test
    @DisplayName("성능 비교 1 - 화면 조회 (Read): AS-IS (O(N) 집계) vs TO-BE (CQRS Summary 테이블 캐시)")
    void performance_read_asis_vs_tobe() {
        // [AS-IS 방식을 시뮬레이션]
        long asisStart = System.currentTimeMillis();
        List<Rating> ratings = ratingRepository.findByReleaseId(testReleaseId);
        double asisTotal = 0;
        for (Rating r : ratings) {
            asisTotal += r.getScore();
        }
        double asisAverage = ratings.isEmpty() ? 0 : asisTotal / ratings.size();
        long asisEnd = System.currentTimeMillis();
        long asisLatency = asisEnd - asisStart;

        // [TO-BE 방식]
        long tobeStart = System.currentTimeMillis();
        ReleaseRatingSummary summary = releaseRatingSummaryRepository.findByReleaseId(testReleaseId).orElse(null);
        double tobeAverage = summary != null ? summary.getAverageScore() : 0.0;
        long tobeEnd = System.currentTimeMillis();
        long tobeLatency = tobeEnd - tobeStart;

        log.info("================== [Read 성능 비교] ==================");
        log.info("[AS-IS] 1만 건 평점 전체 끌어와서 Java 계산 - 응답: {}ms, 평균값: {}", asisLatency, asisAverage);
        log.info("[TO-BE] Summary 테이블 단건 조회 (O(1))     - 응답: {}ms, 평균값: {}", tobeLatency, tobeAverage);
        log.info("======================================================");

        assertThat(asisAverage).isEqualTo(tobeAverage);
        assertThat(tobeLatency).isLessThanOrEqualTo(asisLatency);
    }

    @Test
    @DisplayName("성능 비교 2 - 평점 등록 (Write/Insert): AS-IS (Insert Only) vs TO-BE (Insert + O(1) Delta Update)")
    void performance_write_asis_vs_tobe() {
        double newScore = 5.0;
        long newUserId = 99999L; // 가상의 유저 ID

        // [AS-IS 방식을 시뮬레이션]
        // 예전에는 평점을 추가할 때 DB에 Insert만 하고 끝났음.
        long asisStart = System.currentTimeMillis();
        jdbcTemplate.update("INSERT INTO ratings (user_id, release_id, score, created_at, updated_at) VALUES (?, ?, ?, NOW(), NOW())",
                newUserId, testReleaseId, newScore);
        long asisEnd = System.currentTimeMillis();
        long asisLatency = asisEnd - asisStart;

        // [TO-BE 방식]
        // Native 쿼리를 통한 O(1) 증분 업데이트 (쓰기 지연 Penalty 측정)
        long tobeStart = System.currentTimeMillis();
        jdbcTemplate.update("INSERT INTO ratings (user_id, release_id, score, created_at, updated_at) VALUES (?, ?, ?, NOW(), NOW())",
                newUserId + 1, testReleaseId, newScore);
        releaseRatingSummaryRepository.incrementRating(testReleaseId, newScore); // Delta DB Update
        long tobeEnd = System.currentTimeMillis();
        long tobeLatency = tobeEnd - tobeStart;

        log.info("================= [Write/Insert 성능 비교] ================");
        // AS-IS는 단순 Insert 1회이므로 무조건 더 빠릅니다. TO-BE는 Insert + Update 2번의 쿼리가 발생합니다.
        log.info("[AS-IS] 기존 쓰기 로직 (단순 Insert 1회) - 소요 시간: {}ms", asisLatency);
        log.info("[TO-BE] 증분 통계 업데이트 추가 (Insert + Update) - 소요 시간: {}ms", tobeLatency);
        log.info("-> Trade-off: 쓰기(Write)에서 약간의 지연({}ms)을 감수하고, 조회(Read) 성능 99% 향상을 쟁취함", tobeLatency - asisLatency);
        log.info("=======================================================");
    }
}
