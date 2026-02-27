package com.hipster.rating.repository;

import com.hipster.rating.domain.ReleaseRatingSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReleaseRatingSummaryRepository extends JpaRepository<ReleaseRatingSummary, Long> {
    Optional<ReleaseRatingSummary> findByReleaseId(Long releaseId);

    @Modifying
    @Query(value = "INSERT INTO release_rating_summary (release_id, total_rating_count, average_score, updated_at) " +
                   "VALUES (:releaseId, 1, :score, NOW()) " +
                   "ON DUPLICATE KEY UPDATE " +
                   "average_score = ((average_score * total_rating_count) + :score) / (total_rating_count + 1), " +
                   "total_rating_count = total_rating_count + 1, " +
                   "updated_at = NOW()", nativeQuery = true)
    void incrementRating(@Param("releaseId") Long releaseId, @Param("score") double score);

    @Modifying
    @Query(value = "UPDATE release_rating_summary SET " +
                   "average_score = ((average_score * total_rating_count) - :oldScore + :newScore) / total_rating_count, " +
                   "updated_at = NOW() " +
                   "WHERE release_id = :releaseId", nativeQuery = true)
    void updateRatingScore(@Param("releaseId") Long releaseId, @Param("oldScore") double oldScore, @Param("newScore") double newScore);
}
