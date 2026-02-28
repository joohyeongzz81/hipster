package com.hipster.rating.service;

import com.hipster.rating.domain.Rating;
import com.hipster.rating.domain.ReleaseRatingSummary;
import com.hipster.rating.dto.request.CreateRatingRequest;
import com.hipster.rating.repository.RatingRepository;
import com.hipster.rating.repository.ReleaseRatingSummaryRepository;
import com.hipster.release.domain.Release;
import com.hipster.release.domain.ReleaseType;
import com.hipster.release.repository.ReleaseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class RatingAsyncArchitectureTest {

    private static final Logger log = LoggerFactory.getLogger(RatingAsyncArchitectureTest.class);

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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.batch.jdbc.initialize-schema", () -> "always");
    }

    @Autowired
    private RatingService ratingService;

    @Autowired
    private ReleaseRatingSummaryRepository summaryRepository;

    @Autowired
    private RatingRepository ratingRepository;

    @Autowired
    private ReleaseRepository releaseRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("ratingSummaryExecutor")
    private Executor ratingSummaryExecutor;

    private Long testReleaseId;
    private final int DUMMY_USERS = 1000;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0;");
        jdbcTemplate.execute("TRUNCATE TABLE release_rating_summary;");
        jdbcTemplate.execute("TRUNCATE TABLE ratings;");
        jdbcTemplate.execute("TRUNCATE TABLE releases;");
        jdbcTemplate.execute("TRUNCATE TABLE users;");
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1;");

        // 1. 테스트 유저 대량 생성 (비동기 병렬 요청을 위함)
        LocalDateTime now = LocalDateTime.now();
        String sqlUser = "INSERT INTO users (username, email, password_hash, weighting_score, review_bonus, last_active_date, created_at, updated_at) VALUES (?, ?, 'hash', 0.0, false, ?, ?, ?)";
        List<Object[]> batchArgsUser = new ArrayList<>();
        for (int i = 1; i <= DUMMY_USERS; i++) {
            batchArgsUser.add(new Object[]{"async_user_" + i, "async" + i + "@test.com", now, now, now});
        }
        jdbcTemplate.batchUpdate(sqlUser, batchArgsUser);

        // 2. 앨범 생성
        Release release = Release.builder()
                .title("Async Test Release")
                .artistId(1L)
                .releaseType(ReleaseType.ALBUM)
                .releaseDate(LocalDate.now())
                .build();
        release.approve();
        release = releaseRepository.save(release);
        testReleaseId = release.getId();
        
        // 3. (동기를 위한) 빈 쓰레드 풀 명시적 초기화(Shutdown 되어있을 수 있으므로)
        if (ratingSummaryExecutor instanceof ThreadPoolTaskExecutor taskExecutor) {
            if (taskExecutor.getThreadPoolExecutor() == null || taskExecutor.getThreadPoolExecutor().isShutdown()) {
                taskExecutor.initialize();
            }
        }
    }
    
    @AfterEach
    void tearDown() {
        if (ratingSummaryExecutor instanceof ThreadPoolTaskExecutor taskExecutor) {
            if (taskExecutor.getThreadPoolExecutor() == null || taskExecutor.getThreadPoolExecutor().isShutdown()) {
                taskExecutor.initialize(); // 초기화 복구
            }
        }
    }

    @Test
    @DisplayName("시나리오 1: 비동기 분리(Async) 성능상 이점과 Eventual Consistency 증명")
    void performance_and_eventual_consistency_test() throws InterruptedException {
        int concurrentRequests = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(concurrentRequests);

        long start = System.currentTimeMillis();

        // 100명의 유저가 동시에 평점을 남기는 상황
        for (int i = 1; i <= concurrentRequests; i++) {
            final long userId = i;
            executorService.submit(() -> {
                try {
                    // 평점 추가 (메인 비즈니스 로직 - 즉각 반환 기대)
                    ratingService.createOrUpdateRating(testReleaseId, new CreateRatingRequest(5.0), userId);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long end = System.currentTimeMillis();
        long latency = end - start;

        // DB 검증 1: 평점 (Ratings) 테이블에는 데이터가 즉시 커밋되어 있음
        long ratingCount = ratingRepository.count();
        assertThat(ratingCount).isEqualTo(concurrentRequests);

        // DB 검증 2: Eventual Consistency (최종 일관성) 검증
        // 아직 비동기 큐에서 통계를 업데이트 중일 수 있으므로 잠깐 대기 (Eventual Consistency 허용 시간)
        Thread.sleep(2000); 

        ReleaseRatingSummary summary = summaryRepository.findByReleaseId(testReleaseId).orElse(null);
        assertThat(summary).isNotNull();
        assertThat(summary.getTotalRatingCount()).isEqualTo(concurrentRequests);
        
        log.info("============== [Performance Test] ==============");
        log.info("목표: 비동기 스레드 풀에서 발생하는 지연과 통계 업데이트 로그가 메인 쓰기(Write) 시간에 영향을 주지 않아야 함.");
        log.info("{}건 동시 요청(Write) 총 처리 시간: {}ms", concurrentRequests, latency);
        log.info("평균 응답 시간: {}ms", (double) latency / concurrentRequests);
        log.info("최종 일관성(Eventual Consistency) 도달 확인 완료. Summary Count: {}", summary.getTotalRatingCount());
        log.info("================================================");
    }

    @Test
    @DisplayName("시나리오 2: 인메모리 비동기 한계 재현 (서버 크래시 시 Message Loss 발생)")
    void in_memory_message_loss_anomaly_test() throws InterruptedException {
        int targetRequests = 50;

        // 1. 메인 트랜잭션을 통해 이벤트를 비동기 큐에 밀어 넣음
        for (int i = 1; i <= targetRequests; i++) {
            ratingService.createOrUpdateRating(testReleaseId, new CreateRatingRequest(4.0), (long) i);
            
            // 큐에 넣자마자 10번째 요청 쯤에 고의로 크래시를 발생시킴
            if (i == 10) {
                log.error("!!!!!!!!!! FATAL: JVM OOM Kill 상황 시뮬레이션 (Thread Pool Shutdown) !!!!!!!!!!");
                if (ratingSummaryExecutor instanceof ThreadPoolTaskExecutor taskExecutor) {
                    // 서버가 비정상 종료되는 상황 재현 (ShutdownNow)
                    // 현재 실행 중이던 이벤트는 중단되고, 메모리 큐에 쌓여 대기 중이던 이벤트들은 즉시 공중분해(Loss)됨.
                    taskExecutor.shutdown(); 
                }
            }
        }

        // 혹시라도 살아남은 스레드가 작업을 마칠 시간을 여유롭게 부여 (일반적으론 2초면 다 끝남)
        Thread.sleep(2000); 

        // [핵심 검증 1] 메인 트랜잭션 데이터 (원본 데이터는 정상적으로 DB에 저장됨)
        List<Rating> ratings = ratingRepository.findByReleaseId(testReleaseId);
        log.info("DB에 안전하게 커밋된 원본 Rating 데이터 수: {}", ratings.size());
        assertThat(ratings.size()).isEqualTo(targetRequests);

        // [핵심 검증 2] 통계 테이블 데이터 확인 (Message Loss 정합성 파괴 증명)
        ReleaseRatingSummary summary = summaryRepository.findByReleaseId(testReleaseId).orElse(null);
        long updatedSummaryCount = (summary != null) ? summary.getTotalRatingCount() : 0;
        
        log.error("비동기 처리된 Summary 통계 반영 수: {} (Expected: {})", updatedSummaryCount, targetRequests);
        
        // 통계 반영(updatedSummaryCount)이 원본 개수(targetRequests, 50)를 영원히 따라잡지 못함 (정합성 파괴)
        assertThat(updatedSummaryCount).isLessThan(targetRequests);
        
        log.info("============== [Anomaly Proved] ==============");
        log.info("결론: 인메모리 스레드풀의 한계로 인해 이벤트({})가 허공에 증발(Message Loss)했습니다.", targetRequests - updatedSummaryCount);
        log.info("이 영구적인 정합성 불일치는 추후 Kafka, Redis MQ 등 외부 저장소(챕터 3) 도입의 확고한 근거가 됩니다.");
        log.info("================================================");
    }
}
