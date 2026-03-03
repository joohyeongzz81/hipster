package com.hipster.rating.service;

import com.hipster.rating.BayesianConstants;
import com.hipster.rating.event.RatingEvent;
import com.hipster.rating.repository.ReleaseRatingSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class RatingSummaryService {

    private final ReleaseRatingSummaryRepository releaseRatingSummaryRepository;

    /**
     * RatingEvent를 수신하여 적절한 통계 업데이트를 위임합니다.
     * - isDeleted: 평점 취소 -> decrementRating
     * - isCreated: 평점 신규 등록 -> incrementRating
     * - else:      평점 수정 -> updateRatingScore
     */
    @Transactional
    public void applyRatingEvent(final RatingEvent event) {
        final BigDecimal score = BigDecimal.valueOf(event.newScore());
        final BigDecimal oldScore = BigDecimal.valueOf(event.oldScore());
        final BigDecimal weightingScore = BigDecimal.valueOf(event.weightingScore());

        if (event.isDeleted()) {
            log.debug("RatingSummaryService: DECREMENT releaseId={}", event.releaseId());
            releaseRatingSummaryRepository.decrementRating(event.releaseId(), oldScore, weightingScore, BayesianConstants.M, BayesianConstants.C);
        } else if (event.isCreated()) {
            log.debug("RatingSummaryService: INCREMENT releaseId={}", event.releaseId());
            releaseRatingSummaryRepository.incrementRating(event.releaseId(), score, weightingScore, BayesianConstants.M, BayesianConstants.C);
        } else if (event.oldScore() != event.newScore()) {
            log.debug("RatingSummaryService: UPDATE releaseId={}", event.releaseId());
            releaseRatingSummaryRepository.updateRatingScore(event.releaseId(), oldScore, score, weightingScore, BayesianConstants.M, BayesianConstants.C);
        } else {
            log.debug("RatingSummaryService: No change detected for releaseId={}, skipping.", event.releaseId());
        }
    }
}
