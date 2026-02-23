package com.hipster.batch;

import com.hipster.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class UserWeightingBatchRaceConditionTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("hipster_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.batch.jdbc.initialize-schema", () -> "always");

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private UserWeightingBatchJob userWeightingBatchJob;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        // 데이터가 없는 상태에서 Job이 실행돼도 무방함 (Race Condition 제어가 목적)
    }

    @Test
    @DisplayName("분산 락 적용 후, 3대의 서버가 동시에 스케줄러를 호출해도 단 1번만 배치가 실행된다.")
    void after_distributed_lock_test() throws InterruptedException {
        // given: 3개의 스레드(서버 A, B, C를 모사)와 동시 출발을 위한 Latch 준비
        int threadCount = 3;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // when: 3개 스레드에서 동시에 UserWeightingBatchJob 스케줄러 트리거
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown(); // 스레드 대기 준비 완료
                    startLatch.await();     // 메인 스레드의 출발 신호 대기
                    
                    // 분산 락이 걸린 스케줄러 메서드 호출
                    userWeightingBatchJob.runWeightingRecalculation();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();  // 실행 완료 체크
                }
            });
        }

        readyLatch.await(); // 모든 스레드가 준비될 때까지 대기
        startLatch.countDown(); // 동시에 출발! (> Redisson 락 경합 발생)
        doneLatch.await(); // 3개 스레드 모두 종료 대기

        executorService.shutdown();

        // then: 실제 BATCH_JOB_EXECUTION 테이블에 저장된 실행 횟수(count)가 1인지 검증
        int jobExecutionCount = jobExplorer.findJobInstancesByJobName("weightingRecalculationJob", 0, 10).size();
        
        assertThat(jobExecutionCount)
                .as("동시 호출에도 불구하고 배치는 단 1회만 생성/실행되어야 한다.")
                .isEqualTo(1);
    }
}
