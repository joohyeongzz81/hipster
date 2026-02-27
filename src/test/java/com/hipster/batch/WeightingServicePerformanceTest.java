package com.hipster.batch;

import com.hipster.batch.calculator.WeightingCalculator;
import com.hipster.batch.dto.UserWeightingStatsDto;
import com.hipster.batch.repository.WeightingStatsQueryRepository;
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
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class WeightingServicePerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(WeightingServicePerformanceTest.class);

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
    private WeightingService weightingService;

    @Autowired
    private WeightingStatsQueryRepository queryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private List<User> targetUsers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM reviews");
        jdbcTemplate.execute("DELETE FROM ratings");
        jdbcTemplate.execute("DELETE FROM user_weight_stats");
        jdbcTemplate.execute("DELETE FROM users");

        targetUsers.clear();
        for (int i = 0; i < 1000; i++) { // 1000명의 유저 생성
            User user = User.builder()
                    .username("user_" + i)
                    .email("user_" + i + "@test.com")
                    .passwordHash("hash")
                    .build();
            targetUsers.add(user);
        }
        userRepository.saveAll(targetUsers);

        log.info("Inserting 50,000 dummy ratings and reviews for performance test...");
        String sqlRating = "INSERT INTO ratings (user_id, release_id, score, weighted_score, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)";
        List<Object[]> batchArgsRating = new ArrayList<>();
        
        String sqlReview = "INSERT INTO reviews (user_id, release_id, content, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)";
        List<Object[]> batchArgsReview = new ArrayList<>();

        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 50000; i++) {
            User randomUser = targetUsers.get(i % 1000);
            batchArgsRating.add(new Object[]{randomUser.getId(), (long) i, 4.0, 4.0, now, now});
            batchArgsReview.add(new Object[]{randomUser.getId(), (long) i, "This is a dummy review content", "ACTIVE", now, now});
        }
        
        jdbcTemplate.batchUpdate(sqlRating, batchArgsRating);
        jdbcTemplate.batchUpdate(sqlReview, batchArgsReview);
        log.info("Insert complete.");
    }

    private long getUsedHeapMemory() {
        Runtime.getRuntime().gc();
        return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
    }



    /**
     * AS-IS (Row-by-Row 단건 처리) vs TO-BE (Bulk 처리) 성능 비교 테스트
     * 1000명의 유저에 대한 가중치 재계산 로직 수행 속도 비교
     */
    @Test
    @DisplayName("성능 비교: AS-IS 단건 순차 처리 vs TO-BE Chunk 벌크 집계 처리")
    void performance_comparison_asis_vs_tobe() {
        
        // --- 1. AS-IS 성능 측정 ---
        long asisMemoryBefore = getUsedHeapMemory();
        long asisStartTime = System.currentTimeMillis();

        for (User user : targetUsers) {
            // N+1 쿼리 발생 지점
            weightingService.calculateUserWeightingForBatch(user);
        }

        long asisMemoryAfter = getUsedHeapMemory();
        long asisEndTime = System.currentTimeMillis();
        
        long asisExecutionTime = asisEndTime - asisStartTime;
        long asisMemoryUsed = Math.max(0, asisMemoryAfter - asisMemoryBefore);

        log.info("==================================================");
        log.info("[AS-IS] 1,000명 단건 순차 처리 - 소요 시간: {}ms, 추가 Heap 메모리: {}MB", asisExecutionTime, asisMemoryUsed);

        // 환경 초기화 (메모리 정리)
        jdbcTemplate.execute("DELETE FROM user_weight_stats");
        System.gc();
        try { Thread.sleep(500); } catch (Exception ignored) {}

        // --- 2. TO-BE 성능 측정 ---
        WeightingCalculator calculator = new WeightingCalculator();
        long tobeMemoryBefore = getUsedHeapMemory();
        long tobeStartTime = System.currentTimeMillis();

        // [Bulk Read] 한 번의 쿼리로 1,000명 통계 조회
        List<Long> userIds = targetUsers.stream().map(User::getId).collect(Collectors.toList());
        Map<Long, UserWeightingStatsDto> statsMap = queryRepository.findStatsByUserIds(userIds);

        // [In-Memory Compute] 메모리에서 가중치 계산
        for (User user : targetUsers) {
            UserWeightingStatsDto stats = statsMap.get(user.getId());
            if (stats != null) {
                calculator.calculateWeight(stats, user.getLastActiveDate());
            }
        }

        long tobeMemoryAfter = getUsedHeapMemory();
        long tobeEndTime = System.currentTimeMillis();

        long tobeExecutionTime = tobeEndTime - tobeStartTime;
        long tobeMemoryUsed = Math.max(0, tobeMemoryAfter - tobeMemoryBefore);

        log.info("[TO-BE] 1,000명 Bulk 벌크 처리 - 소요 시간: {}ms, 추가 Heap 메모리: {}MB", tobeExecutionTime, tobeMemoryUsed);
        log.info("==================================================");
        log.info("성능 향상: 소요 시간 약 {} 배 단축", (double) asisExecutionTime / Math.max(1, tobeExecutionTime));

        // 검증 1: TO-BE 방식이 AS-IS 방식보다 실행 시간이 확연히(최소 2배 이상) 빨라야 함
        assertThat(tobeExecutionTime)
                .as("TO-BE 방식은 AS-IS 병목을 줄여 실행 시간이 훨씬 짧아야 한다.")
                .isLessThan(asisExecutionTime / 2);
    }
}
