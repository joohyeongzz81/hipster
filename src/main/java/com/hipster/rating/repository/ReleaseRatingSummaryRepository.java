package com.hipster.rating.repository;

import com.hipster.rating.domain.ReleaseRatingSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

public interface ReleaseRatingSummaryRepository extends JpaRepository<ReleaseRatingSummary, Long> {
    
    Optional<ReleaseRatingSummary> findByReleaseId(Long releaseId);

    /**
     * 평점 신규 등록 (UPSERT)
     * [경고] MySQL UPDATE 평가 순서 의존성 주의!
     * bayesian_score 갱신 로직이 반드시 weighted_score_sum, weighted_count_sum 갱신보다 '먼저' 위치해야 합니다.
     * 순서가 바뀌면 델타값이 이중으로 더해지는 치명적 버그가 발생합니다.
     */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO release_rating_summary (release_id, weighted_score_sum, weighted_count_sum, bayesian_score, updated_at) " +
                   "VALUES (:releaseId, (:score * :weightingScore), :weightingScore, (:c * :m + (:score * :weightingScore)) / (:c + :weightingScore), NOW()) " +
                   "ON DUPLICATE KEY UPDATE " +
                   "bayesian_score = IF(:eventTs > batch_synced_at OR batch_synced_at IS NULL, (:c * :m + (weighted_score_sum + (:score * :weightingScore))) / (:c + (weighted_count_sum + :weightingScore)), bayesian_score), " +
                   "weighted_score_sum = IF(:eventTs > batch_synced_at OR batch_synced_at IS NULL, weighted_score_sum + (:score * :weightingScore), weighted_score_sum), " +
                   "weighted_count_sum = IF(:eventTs > batch_synced_at OR batch_synced_at IS NULL, weighted_count_sum + :weightingScore, weighted_count_sum), " +
                   "updated_at = IF(:eventTs > batch_synced_at OR batch_synced_at IS NULL, NOW(), updated_at)", nativeQuery = true)
    void incrementRating(@Param("releaseId") Long releaseId,
                         @Param("score") BigDecimal score,
                         @Param("weightingScore") BigDecimal weightingScore,
                         @Param("m") BigDecimal m,
                         @Param("c") BigDecimal c,
                         @Param("eventTs") LocalDateTime eventTs);

    /**
     * 평점 점수 수정
     * 기존 가중합에서 (과거점수 * 현재신뢰도)를 빼고, (새점수 * 현재신뢰도)를 더합니다.
     * count_sum은 변하지 않으므로 bayesian_score 계산 시 기존값을 그대로 사용합니다.
     */
    @Transactional
    @Modifying
    @Query(value = "UPDATE release_rating_summary SET " +
                   "bayesian_score = (:c * :m + weighted_score_sum - (:oldScore * :weightingScore) + (:newScore * :weightingScore)) / (:c + weighted_count_sum), " +
                   "weighted_score_sum = weighted_score_sum - (:oldScore * :weightingScore) + (:newScore * :weightingScore), " +
                   "updated_at = NOW() " +
                   "WHERE release_id = :releaseId " +
                   "AND (:eventTs > batch_synced_at OR batch_synced_at IS NULL)", nativeQuery = true)
    void updateRatingScore(@Param("releaseId") Long releaseId,
                           @Param("oldScore") BigDecimal oldScore,
                           @Param("newScore") BigDecimal newScore,
                           @Param("weightingScore") BigDecimal weightingScore,
                           @Param("m") BigDecimal m,
                           @Param("c") BigDecimal c,
                           @Param("eventTs") LocalDateTime eventTs);

    /**
     * 평점 취소 (삭제)
     * 기존 가중합과 가중수에서 취소된 유저의 지분을 완전히 빼냅니다.
     * [방어 로직] 분모인 (C + weighted_count_sum)가 0이 되는 것은 상수 C(예: 100) 덕분에 발생하지 않습니다.
     */
    @Transactional
    @Modifying
    @Query(value = "UPDATE release_rating_summary SET " +
                   "bayesian_score = (:c * :m + (weighted_score_sum - (:oldScore * :weightingScore))) / (:c + (weighted_count_sum - :weightingScore)), " +
                   "weighted_score_sum = weighted_score_sum - (:oldScore * :weightingScore), " +
                   "weighted_count_sum = weighted_count_sum - :weightingScore, " +
                   "updated_at = NOW() " +
                   "WHERE release_id = :releaseId " +
                   "AND (:eventTs > batch_synced_at OR batch_synced_at IS NULL)", nativeQuery = true)
    void decrementRating(@Param("releaseId") Long releaseId,
                         @Param("oldScore") BigDecimal oldScore,
                         @Param("weightingScore") BigDecimal weightingScore,
                         @Param("m") BigDecimal m,
                         @Param("c") BigDecimal c,
                         @Param("eventTs") LocalDateTime eventTs);
}