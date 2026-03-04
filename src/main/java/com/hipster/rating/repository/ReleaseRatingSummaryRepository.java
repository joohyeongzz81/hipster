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
     */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO release_rating_summary (release_id, weighted_score_sum, weighted_count_sum, updated_at) " +
                   "VALUES (:releaseId, (:score * :weightingScore), :weightingScore, NOW()) " +
                   "ON DUPLICATE KEY UPDATE " +
                   "weighted_score_sum = IF(:eventTs > batch_synced_at OR batch_synced_at IS NULL, weighted_score_sum + (:score * :weightingScore), weighted_score_sum), " +
                   "weighted_count_sum = IF(:eventTs > batch_synced_at OR batch_synced_at IS NULL, weighted_count_sum + :weightingScore, weighted_count_sum), " +
                   "updated_at = IF(:eventTs > batch_synced_at OR batch_synced_at IS NULL, NOW(), updated_at)", nativeQuery = true)
    void incrementRating(@Param("releaseId") Long releaseId,
                         @Param("score") BigDecimal score,
                         @Param("weightingScore") BigDecimal weightingScore,
                         @Param("eventTs") LocalDateTime eventTs);

    /**
     * 평점 점수 수정
     * 기존 가중합에서 (과거점수 * 현재신뢰도)를 빼고, (새점수 * 현재신뢰도)를 더합니다.
     */
    @Transactional
    @Modifying
    @Query(value = "UPDATE release_rating_summary SET " +
                   "weighted_score_sum = weighted_score_sum - (:oldScore * :weightingScore) + (:newScore * :weightingScore), " +
                   "updated_at = NOW() " +
                   "WHERE release_id = :releaseId " +
                   "AND (:eventTs > batch_synced_at OR batch_synced_at IS NULL)", nativeQuery = true)
    void updateRatingScore(@Param("releaseId") Long releaseId,
                           @Param("oldScore") BigDecimal oldScore,
                           @Param("newScore") BigDecimal newScore,
                           @Param("weightingScore") BigDecimal weightingScore,
                           @Param("eventTs") LocalDateTime eventTs);

    /**
     * 평점 취소 (삭제)
     * 기존 가중합과 가중수에서 취소된 유저의 지분을 완전히 빼냅니다.
     */
    @Transactional
    @Modifying
    @Query(value = "UPDATE release_rating_summary SET " +
                   "weighted_score_sum = weighted_score_sum - (:oldScore * :weightingScore), " +
                   "weighted_count_sum = weighted_count_sum - :weightingScore, " +
                   "updated_at = NOW() " +
                   "WHERE release_id = :releaseId " +
                   "AND (:eventTs > batch_synced_at OR batch_synced_at IS NULL)", nativeQuery = true)
    void decrementRating(@Param("releaseId") Long releaseId,
                         @Param("oldScore") BigDecimal oldScore,
                         @Param("weightingScore") BigDecimal weightingScore,
                         @Param("eventTs") LocalDateTime eventTs);

    /**
     * 글로벌 가중 평균 C를 단일 쿼리로 산출한다.
     * C = SUM(weighted_score_sum) / SUM(weighted_count_sum)
     * 평점 데이터가 전혀 없으면 NULL이 반환될 수 있으므로 Optional로 감쌈.
     * 배치 사이클 시작 시 1회 호출하여 전체 앨범에 동일한 C를 재사용한다.
     */
    @Query(value = "SELECT SUM(weighted_score_sum) / NULLIF(SUM(weighted_count_sum), 0) " +
                   "FROM release_rating_summary", nativeQuery = true)
    Optional<BigDecimal> calculateGlobalWeightedAverage();
}