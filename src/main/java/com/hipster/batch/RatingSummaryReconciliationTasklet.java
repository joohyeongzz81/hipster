package com.hipster.batch;

import com.hipster.batch.repository.WeightingStatsQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 유저 가중치 배치(weightingStep) 완료 후 실행되는 타겟 재집계 Tasklet.
 * 가중치가 변경된 유저를 감지하고, 해당 유저의 평점이 있는 앨범만 골라내어
 * release_rating_summary를 최신 weightingScore 기준으로 재집계한다.
 *
 * ratings 테이블은 읽기 전용(Source of Truth)으로만 사용하며, 수정하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RatingSummaryReconciliationTasklet implements Tasklet {

    private final WeightingStatsQueryRepository queryRepository;

    @Override
    @Transactional
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        log.info("[Reconciliation] 타겟 재집계 시작 - 가중치 변경 유저 감지 중...");

        final List<Long> changedUserIds = queryRepository.findChangedUserIds();

        if (changedUserIds.isEmpty()) {
            log.info("[Reconciliation] 가중치 변경 유저 없음. 재집계 스킵.");
            return RepeatStatus.FINISHED;
        }

        log.info("[Reconciliation] 가중치 변경 유저 {}명 감지. 영향받은 앨범 재집계 시작...", changedUserIds.size());

        queryRepository.reconcileAffectedReleases(changedUserIds);

        log.info("[Reconciliation] 타겟 재집계 완료.");
        return RepeatStatus.FINISHED;
    }
}
