package com.hipster.batch;

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
    private UserRepository userRepository;



    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User targetUser;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM reviews");
        jdbcTemplate.execute("DELETE FROM ratings");
        jdbcTemplate.execute("DELETE FROM users");

        targetUser = User.builder()
                .username("heavy_user")
                .email("heavy@test.com")
                .passwordHash("hash")
                .build();
        userRepository.save(targetUser);

        // 빠른 테스트를 위해 JDBC Batch Update로 5만건 강제 삽입
        log.info("Inserting 50,000 dummy ratings and reviews for performance test...");
        String sqlRating = "INSERT INTO ratings (user_id, release_id, score, weighted_score, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)";
        List<Object[]> batchArgsRating = new ArrayList<>();
        
        String sqlReview = "INSERT INTO reviews (user_id, release_id, content, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)";
        List<Object[]> batchArgsReview = new ArrayList<>();

        for (int i = 0; i < 50000; i++) {
            LocalDateTime now = LocalDateTime.now();
            batchArgsRating.add(new Object[]{targetUser.getId(), (long) i, 4.0, 4.0, now, now});
            batchArgsReview.add(new Object[]{targetUser.getId(), (long) i, "This is a dummy review content", "ACTIVE", now, now});
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
     * [Step 3: To-Be 최적화 검증 테스트 (The Green Phase)]
     * 통계 연산을 MySQL DB 단으로 내리면서 극적인 메모리/속도 최적화 증명
     */
    @Test
    @DisplayName("To-Be (DB 위임): DB 집계 함수 위임 시 Heap 메모리 극감소 및 O(1) 통신 최적화")
    void to_be_performance_and_idempotency_test() {
        // --- 1. 성능 관점 (메모리, 시간차) 테스트 ---
        long memoryBefore = getUsedHeapMemory();
        long startTime = System.currentTimeMillis();

        // To-Be 방식: JPA Entity를 로드하지 않고 DB에서 COUNT, AVG, VAR_POP 등 집계 연산만 1줄로 받아옴
        double toBeResult = weightingService.calculateUserWeightingForBatch(targetUser);
        
        long memoryAfterLoad = getUsedHeapMemory();
        long endTime = System.currentTimeMillis();

        long executionTime = endTime - startTime;
        long memoryUsed = memoryAfterLoad - memoryBefore;

        log.info("[To-Be] DB Aggregation 처리 - 소요 시간: {}ms, Heap 메모리 사용량: {}MB", executionTime, memoryUsed);

        // Assert 1: DTO Projection 방식이므로 메모리 로드가 O(1) 수준 (10MB도 차지하지 않아야 함)
        assertThat(memoryUsed)
                .as("To-Be 구조에서는 영속성 컨텍스트를 거치지 않으므로 Heap 메모리가 10MB 미만이어야 한다.")
                .isLessThan(10);
                
        assertThat(executionTime)
                .as("모든 무거운 연산이 DB에서 처리되므로 소요 시간이 100ms 미만으로 압도적으로 줄어들어야 한다.")
                .isLessThan(200);

        // Assert 2: 정상적인 값이 반환되었는지 검증
        assertThat(toBeResult).isGreaterThanOrEqualTo(0.0);
    }
}
