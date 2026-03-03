package com.hipster.batch;

import com.hipster.batch.repository.AntiEntropyQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Anti-Entropy Full 배치.
 *
 * 매일 새벽 03:00에 ratings JOIN users를 전체 재집계하여
 * 실시간 파이프라인(RabbitMQ At-Least-Once)의 이중 누적 오염을 자동 교정합니다.
 *
 * 실행 시간 모니터링 기준:
 *  - 1시간 초과: 쿼리 최적화 검토
 *  - 2시간 초과: 파티셔닝 도입 검토
 *  - 4시간 초과: 즉각 대응 (새벽 저점 구간 초과)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AntiEntropyBatchJob {

    private final AntiEntropyQueryRepository antiEntropyQueryRepository;

    @Scheduled(cron = "${hipster.batch.anti-entropy-cron:0 0 3 * * ?}")
    @SchedulerLock(name = "antiEntropyFullBatch", lockAtLeastFor = "30s", lockAtMostFor = "4h")
    public void runAntiEntropyFull() {
        final LocalDateTime batchSyncedAt = LocalDateTime.now();
        log.info("[AntiEntropy] Full 재집계 시작 batchSyncedAt={}", batchSyncedAt);

        try {
            final List<Long> allReleaseIds = antiEntropyQueryRepository.findAllReleaseIds();
            final List<List<Long>> chunks = AntiEntropyQueryRepository.partition(allReleaseIds);

            log.info("[AntiEntropy] 대상 앨범 수={}, 청크 수={}", allReleaseIds.size(), chunks.size());

            int processedChunks = 0;
            for (final List<Long> chunk : chunks) {
                antiEntropyQueryRepository.reconcileChunk(chunk, batchSyncedAt);
                processedChunks++;
                if (processedChunks % 10 == 0) {
                    log.info("[AntiEntropy] 진행 중: {}/{} 청크 완료", processedChunks, chunks.size());
                }
            }

            log.info("[AntiEntropy] Full 재집계 완료. 총 처리 앨범 수={}", allReleaseIds.size());

        } catch (Exception e) {
            log.error("[AntiEntropy] Full 재집계 실패. batchSyncedAt={}", batchSyncedAt, e);
        }
    }
}
