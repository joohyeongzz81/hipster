package com.hipster.rating.service;

import com.hipster.global.config.RabbitMqConfig;
import com.hipster.rating.domain.ReleaseRatingSummary;
import com.hipster.rating.dto.request.CreateRatingRequest;
import com.hipster.rating.repository.RatingRepository;
import com.hipster.rating.repository.ReleaseRatingSummaryRepository;
import com.hipster.release.domain.Release;
import com.hipster.release.domain.ReleaseType;
import com.hipster.release.repository.ReleaseRepository;
import java.time.LocalDate;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.mock.mockito.MockBean;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;


@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public class RatingRabbitMqArchitectureTest {

    private static final Logger log = LoggerFactory.getLogger(RatingRabbitMqArchitectureTest.class);

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMQContainer = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management"));

    @Autowired
    private RatingService ratingService;

    @Autowired
    private RatingRepository ratingRepository;

    @Autowired
    private ReleaseRatingSummaryRepository summaryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReleaseRepository releaseRepository;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    @Autowired
    private RabbitListenerEndpointRegistry endpointRegistry;

    @MockBean
    private LockProvider lockProvider;

    private Long testReleaseId;
    private List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ratingRepository.deleteAllInBatch();
        summaryRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        releaseRepository.deleteAllInBatch();

        Release release = Release.builder()
                .title("RabbitMQ Test Album")
                .artistId(1L)
                .releaseType(ReleaseType.ALBUM)
                .releaseDate(LocalDate.now())
                .build();
        release.approve();
        Release savedRelease = releaseRepository.save(release);
        testReleaseId = savedRelease.getId();

        for (int i = 0; i < 50; i++) {
            User user = User.builder()
                    .email("test" + i + "@example.com")
                    .username("user" + i)
                    .build();

            user.changePassword("asd");
            userIds.add(userRepository.save(user).getId());
        }
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (endpointRegistry != null) {
            endpointRegistry.stop();
        }
    }

    @Test
    @DisplayName("TO-BE [Chapter 3]: 장애 격리(Fault Isolation) 및 데이터 영구 보존(Zero Message Loss) 증명")
    void rabbitMq_fault_isolation_and_durability_test() throws InterruptedException {
        // [시나리오 세팅]
        // 1. 통계 서버(Consumer)에 장애(OOM, 셧다운)가 발생하여 리스너가 작동을 멈췄다고 가정합니다.
        MessageListenerContainer summaryContainer = endpointRegistry.getListenerContainer("ratingSummaryListener");
        summaryContainer.stop();
        assertThat(summaryContainer.isRunning()).isFalse();

        // 2. 동시성 환경에서 50명의 유저가 평점 작성 (대규모 트래픽 스파이크)
        int concurrentRequests = 50;
        CountDownLatch latch = new CountDownLatch(concurrentRequests);
        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);

        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        for (int i = 0; i < concurrentRequests; i++) {
            final Long userId = userIds.get(i);
            executor.submit(() -> {
                try {
                    CreateRatingRequest request = new CreateRatingRequest(5.0);
                    ratingService.createOrUpdateRating(testReleaseId, request, userId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("Error in thread: ", e);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        log.info("Successfully created/updated ratings: {}", successCount.get());

        // 비동기 파이프라인 전파 대기
        Thread.sleep(2000);

        // [증명 1: 완벽한 장애 격리 (Fault Isolation)]
        // 통계 Consumer 는 뻗어있는 상태이지만, 별도로 구독 중인 User Activity Consumer 는 정상 작동 완료되어야 함.
        for (Long userId : userIds) {
            User user = userRepository.findById(userId).orElseThrow();
            assertThat(user.getLastActiveDate()).isNotNull();
        }
        log.info("============== [Fault Isolation Proved] ==============");
        log.info("결론: 하나의 이벤트(RatingCreated)가 Fanout되어 통계와 유저 도메인으로 나뉘었고,");
        log.info("통계 컨슈머 장애가 유저 활동 갱신 컨슈머에 전혀 영향을 주지 않는 완벽한 격리에 성공했습니다.");

        // [증명 2: 영구 보존 (Zero Message Loss)]
        // 통계 컨슈머가 멈춘 상태이므로 DB의 Summary 누적 카운트는 반영되지 않은 채(0)여야 함
        long currentTotal = summaryRepository.findByReleaseId(testReleaseId)
                .map(ReleaseRatingSummary::getTotalRatingCount)
                .orElse(0L);
        assertThat(currentTotal).isEqualTo(0L);

        // 이전 장(인메모리 큐)에서는 여기서 40건의 이벤트가 공중분해(Loss)되었음.
        // 현재 RabbitMQ 아키텍처에서는 해당 50건이 브로커의 큐 버퍼 안에 안전히(Persisted) 보관되어 있어야 함.
        Properties properties = rabbitAdmin.getQueueProperties(RabbitMqConfig.RATING_SUMMARY_QUEUE);
        int messageCount = (Integer) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
        
        log.info("============== [Zero Message Loss Proved] ==============");
        log.info("통계 컨슈머 셧다운 상태에서 유실되지 않고 버퍼링된 큐 메시지 수: {} (Expected: 50)", messageCount);
        log.info("결론: 챕터 2의 치명적 한계(메시지 공중분해)를 극복하고, 디스크(Broker) 상에 이벤트가 100% 보존됨을 증명했습니다.");
        assertThat(messageCount).isEqualTo(50);

        // [증명 3: 장애 복구 후 재처리 (Disaster Recovery & Redelivery)]
        log.info("============== [Disaster Recovery Proved] ==============");
        log.info("에러가 해결되어 통계 컨슈머가 재시작(Recovery) 되었다고 가정합니다...");
        summaryContainer.start();
        
        // 큐에 밀려있던 50건의 메시지가 다시 Consumer(Listener)로 빨려들어가 처리될 때까지 대기
        Thread.sleep(3000); 

        ReleaseRatingSummary recoveredSummary = summaryRepository.findByReleaseId(testReleaseId).orElseThrow();
        log.info("장애 복구 후 정상적으로 지연 반영된 통계 건수: {} (Expected: 50)", recoveredSummary.getTotalRatingCount());
        assertThat(recoveredSummary.getTotalRatingCount()).isEqualTo(50L);
        log.info("=====================================================");
    }
}
