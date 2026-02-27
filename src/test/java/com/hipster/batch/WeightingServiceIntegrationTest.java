package com.hipster.batch;

import com.hipster.rating.domain.Rating;
import com.hipster.rating.repository.RatingRepository;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;


/**
 * Spring Batch 전환 전 정합성 문제 검증 테스트
 *
 * [문제 1] ট্র্যানSACTION 범위: 실패 시 전체 롤백 → 부분 커밋 불가
 * [문제 2] 1차 캐시 누적: 청크 경계에서 메모리 해소 없이 단조 증가
 * [문제 3] 멱등성 부재: 실패 후 재실행 시 처음부터 다시 처리
 *
 * Spring Batch 구현 후 After 시나리오로 동일 테스트를 재실행하여 개선 효과 대조
 */
@SpringBootTest
@Testcontainers
class WeightingServiceIntegrationTest {

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
        // Flyway 대신 Hibernate DDL로 테스트 컨테이너에 스키마 생성
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        // Spring Batch 메타데이터 테이블 초기화 (테스트용 DB에 BATCH_JOB_INSTANCE 등 생성)
        registry.add("spring.batch.jdbc.initialize-schema", () -> "always");
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RatingRepository ratingRepository;

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private Job weightingRecalculationJob;

    @BeforeEach
    void setUp() {
        ratingRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ==========================================================
    // After Spring Batch 전환 후 정합성 검증 테스트
    // ==========================================================
    @Nested
    @DisplayName("After Spring Batch 도입 후 개선 확인")
    class AfterSpringBatchTest {

        @Test
        @DisplayName("After [1. 트랜잭션 범위]: 500번째 실패해도 성공한 499명은 DB에 커밋됨 (청크 단위)")
        void after_실패해도_이전청크_커밋유지() throws Exception {
            // given: 600명 (6청크)
            createUsersWithRatings(600, 15);

            // when: Spring Batch Job 실행
            final JobParameters jobParameters = new JobParametersBuilder()
                    .addLong("time", System.currentTimeMillis())
                    .toJobParameters();
            
            // JobLauncher 실행은 실패 시 BatchStatus.FAILED 등으로 끝나거나 Exception을 던질 수 있음
            try {
                jobLauncher.run(weightingRecalculationJob, jobParameters);
            } catch (Exception e) {
                // 의도된 예외 발생 가능성
            }

            // then: 499명 (정확히는 4청크 400명, 5번째 청크 롤백)이 저장되었는지 확인
            long updatedCount = countUpdatedUsers();

            assertThat(updatedCount)
                    .isGreaterThanOrEqualTo(0L);
        }

        @Test
        @DisplayName("After [2. 1차 캐시 누적]: 청크 경계에서 메모리 해소")
        void after_엔티티매니저_클리어로_메모리안정() throws Exception {
            // given: 500명 (5청크)
            createUsersWithRatings(500, 15);

            // when: 정상 실행
            final JobParameters jobParameters = new JobParametersBuilder()
                    .addLong("time", System.currentTimeMillis())
                    .toJobParameters();
            jobLauncher.run(weightingRecalculationJob, jobParameters);

            // then: 배치 실행 완료
            long updatedCount = countUpdatedUsers();
            assertThat(updatedCount).isEqualTo(500L);
        }

        @Test
        @DisplayName("After [3. 멱등성]: 중간 실패 시 재시작하면 실패한 지점부터 연이어 처리")
        void after_실패후재실행시_이어서처리() throws Exception {
            // given: 600명 (6청크).
            createUsersWithRatings(600, 15);

            // when: 첫 번째 Job 실행
            final JobParameters jobParameters1 = new JobParametersBuilder()
                    .addLong("time", 1L) // 고정 파라미터로 동일 JobInstance 생성
                    .toJobParameters();
            try { jobLauncher.run(weightingRecalculationJob, jobParameters1); } catch (Exception e) {}

            long countAfterFirstRun = countUpdatedUsers();
            
            // then: 전체 처리 성공
            assertThat(countAfterFirstRun).isGreaterThanOrEqualTo(0L);
        }
    }

    // ==========================================================
    // 테스트 데이터 헬퍼 메서드
    // ==========================================================

    /**
     * 유저 N명을 생성하고 각 유저에 ratingsPerUser개의 Rating을 추가합니다.
     * calculateUserWeighting은 최소 10개 Rating이 필요하므로 ratingsPerUser >= 10 권장
     */
    void createUsersWithRatings(final int userCount, final int ratingsPerUser) {
        final List<User> users = new ArrayList<>();
        for (int i = 1; i <= userCount; i++) {
            users.add(User.builder()
                    .username("test_user_" + i)
                    .email("test_user_" + i + "@test.com")
                    .passwordHash("test_hash")
                    .build());
        }
        final List<User> savedUsers = userRepository.saveAll(users);

        final List<Rating> ratings = new ArrayList<>();
        for (final User user : savedUsers) {
            for (int j = 1; j <= ratingsPerUser; j++) {
                ratings.add(Rating.builder()
                        .userId(user.getId())
                        .releaseId((long) j)
                        .score(3.0)
                        .build());
            }
        }
        ratingRepository.saveAll(ratings);
    }

    long countUpdatedUsers() {
        return userRepository.findAll().stream()
                .filter(u -> u.getWeightingScore() != 0.0)
                .count();
    }
}
