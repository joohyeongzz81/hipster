package com.hipster.batch;

import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

    @DynamicPropertySource
    static void configureProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.batch.jdbc.initialize-schema", () -> "always");
    }

    @Autowired
    private UserWeightingBatchJob userWeightingBatchJob;

    @Autowired
    private JobExplorer jobExplorer;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private org.springframework.batch.core.launch.JobLauncher jobLauncher;

    @Autowired
    private org.springframework.batch.core.Job weightingRecalculationJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS shedlock(name VARCHAR(64) NOT NULL, lock_until TIMESTAMP(3) NOT NULL, locked_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), locked_by VARCHAR(255) NOT NULL, PRIMARY KEY (name))");
        jdbcTemplate.execute("DELETE FROM shedlock");
        userRepository.deleteAll();
    }

    /**
     * [Test Scenario 0: As-Is 장애 완벽 재현 (The Red Phase)]
     * 프로덕션 장애 시나리오: ShedLock 기반 분산 락(또는 Redisson)이 적용되지 않은 레거시 환경에서,
     * 다중 인스턴스가 동시에 스케줄러를 트리거할 경우의 치명적 장애를 재현합니다.
     * 고정 파라미터(예: 오늘 날짜)를 주입하는 상황에서, Spring Batch의 메타데이터 단 경합이 발생하여
     * JobExecutionAlreadyRunningException 등 동시성 예외가 터지는 것을 증명합니다.
     */
    @Test
    @DisplayName("ShedLock 적용 전: 고정 파라미터로 3대 서버 동시 트리거 시 Spring Batch 동시성 예외 발생")
    void test_scenario_0_as_is_concurrency_failure() throws Exception {
        int beforeCount = jobExplorer.findJobInstancesByJobName("weightingRecalculationJob", 0, 100).size();

        int threadCount = 3;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        // 예외 발생 횟수를 카운트하기 위한 변수
        java.util.concurrent.atomic.AtomicInteger exceptionCount = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await(); // 3개 스레드 동시 출발 대기

                    // '오늘 날짜' 등 동일한 파라미터가 들어가는 상황 모사 (고정 파라미터)
                    JobParameters jobParameters = new JobParametersBuilder()
                            .addLong("run.date", 20240224L)
                            .toJobParameters();

                    jobLauncher.run(weightingRecalculationJob, jobParameters);
                } catch (Exception e) {
                    // Spring Batch 메타데이터 테이블 경합으로 인한 예외 발생 (JobExecutionAlreadyRunningException 등)
                    exceptionCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown(); // 일제히 스케줄러 트리거
        doneLatch.await();
        executorService.shutdown();

        int afterCount = jobExplorer.findJobInstancesByJobName("weightingRecalculationJob", 0, 100).size();
        int newInstances = afterCount - beforeCount;

        // Assert 1: 동일한 파라미터이므로 배치는 1번만 실행됨 (또는 경합 실패로 전혀 안 돌 수도 있음)
        assertThat(newInstances)
                .as("고정 파라미터이므로 BATCH_JOB_INSTANCE는 1개만 생성되어야 한다.")
                .isLessThanOrEqualTo(1);

        // Assert 2: 나머지 스레드에서는 Spring Batch 프레임워크 레벨의 동시성 예외가 터져야 함
        assertThat(exceptionCount.get())
                .as("분산 락이 없으면, 1개의 스레드를 제외한 나머지 스레드들은 메타데이터 경합 예외를 던진다.")
                .isGreaterThan(0);
    }

    /**
     * [Test Scenario 1: To-Be ShedLock 방어 검증 (The Green Phase)]
     * 해결책 적용 시나리오: @SchedulerLock이 적용되어 다중 인스턴스의 동시 호출로부터 보호합니다.
     * 각 인스턴스가 미세한 오차로 스케줄링 진입점에 도달하더라도,
     * 메인 DB의 락 테이블(shedlock)을 선점한 1대의 인스턴스만 실제 배치 로직을 수행하고,
     * 나머지 2대의 인스턴스는 경합에서 탈락하여 예외 없이 우아하게 실행을 스킵(Skip)함을 증명합니다.
     */
    @Test
    @DisplayName("ShedLock 적용 후: 3대의 서버가 동시 트리거해도 ShedLock 방어선에 의해 1번만 실행됨")
    void test_scenario_1_to_be_shedlock_defense() throws Exception {
        int beforeCount = jobExplorer.findJobInstancesByJobName("weightingRecalculationJob", 0, 100).size();

        int threadCount = 3;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    // 각 윈도우 스레드마다 50ms 오차 모사
                    Thread.sleep(50L * threadIndex);

                    // ShedLock AOP가 적용된 래핑 메서드 직접 호출
                    userWeightingBatchJob.runWeightingRecalculation();
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await();
        executorService.shutdown();

        int afterCount = jobExplorer.findJobInstancesByJobName("weightingRecalculationJob", 0, 100).size();
        int newInstances = afterCount - beforeCount;

        // Assert: 3개 스레드가 경합했으나 예외 없이 단 1번만 Job이 수행됨을 증명
        assertThat(newInstances)
                .as("ShedLock이 활성화된 환경에서는 단 1회만 스케줄링이 진입하여 Job이 실행된다.")
                .isEqualTo(1);
    }

    /**
     * [Test Scenario 2: To-Be 락 유실 대비 멱등성(Idempotency) 검증]
     * 엣지 케이스 시나리오: 인프라 장애(DB Failover 중 Lock 테이블 유실 등)로 인해 ShedLock 방어선이 뚫리고,
     * 동일한 데이터 범위를 타겟으로 배치가 강제 2번 실행되었다고 가정(Fault Injection)합니다.
     * 비즈니스 로직(Data Layer) 내부에 구현된 절대값 기반 업데이트(멱등성 설계)가 작동하여
     * 비즈니스 데이터(가중치 점수 등)가 무한정 누적 갱신되는 갱신 손실을 막고 일관성을 보장함을 증명합니다.
     */
    @Test
    @DisplayName("ShedLock 유실 엣지 케이스: 배치가 2연속 실행(뚫림)되더라도 가중치 점수는 멱등적으로 동일하다")
    void test_scenario_2_to_be_idempotency_defense() throws Exception {
        // given: 테스트용 유저를 1명 생성해 DB에 적재합니다.
        User testUser = User.builder()
                .username("test_idempotent")
                .email("idempotent@test.com")
                .passwordHash("password_hash")
                .build();
        userRepository.save(testUser);

        // 첫 번째 실행 강제 발생 (정상 동작)
        JobParameters params1 = new JobParametersBuilder()
                .addLong("run.id", 1000L)
                .toJobParameters();
        jobLauncher.run(weightingRecalculationJob, params1);

        Double scoreAfterFirstRun = userRepository.findById(testUser.getId())
                .orElseThrow()
                .getWeightingScore();

        // 두 번째 실행 강제 발생 (락 유실 및 중복 실행 발생 상황 모사)
        JobParameters params2 = new JobParametersBuilder()
                .addLong("run.id", 2000L) // 시스템 장애로 다른 런 아이디로 Job 재요청
                .toJobParameters();
        jobLauncher.run(weightingRecalculationJob, params2);

        Double scoreAfterSecondRun = userRepository.findById(testUser.getId())
                .orElseThrow()
                .getWeightingScore();

        // Assert: 두 번째 실행에도 무의미하게 '더하기 연산(+)'이 되지 않고, 첫 번째와 동일한 score 여야 함.
        assertThat(scoreAfterSecondRun)
                .as("멱등성 보장 검증: 스케줄러 중복 방어가 뚫려서 2번 실행되어도 비즈니스 데이터는 완벽히 동일해야 한다")
                .isEqualTo(scoreAfterFirstRun);
    }
}
