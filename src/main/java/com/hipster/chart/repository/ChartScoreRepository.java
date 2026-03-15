package com.hipster.chart.repository;

import com.hipster.chart.domain.ChartScore;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChartScoreRepository extends JpaRepository<ChartScore, Long>, ChartScoreRepositoryCustom {

    Optional<ChartScore> findByRelease_Id(Long releaseId);

    List<ChartScore> findTop100ByOrderByBayesianScoreDesc();

    List<ChartScore> findAllByOrderByBayesianScoreDesc(Pageable pageable);

    List<ChartScore> findTopNByOrderByBayesianScoreDesc(Pageable pageable);

    Optional<ChartScore> findFirstByOrderByLastUpdatedDesc();

    @Query("select max(cs.lastUpdated) from ChartScore cs")
    Optional<LocalDateTime> findMaxLastUpdated();

    @Query("select cs from ChartScore cs join fetch cs.release r where r.id in :releaseIds")
    List<ChartScore> findAllWithReleaseByReleaseIds(@Param("releaseIds") Collection<Long> releaseIds);

    /*
    // 챕터 1/2에서 사용하던 AS-IS JPQL + Native Query 방식 (단일 텍스트 쿼리 + 파라미터 기반 동적 쿼리)
    // - Service 계층에 무수한 방어 로직(if 분기) 강제
    // - DB 옵티마이저가 인덱스를 타지 못하게 유도
    // 챕터 3 서술(QueryDSL) 전 before/after 비교를 위해 남겨둠
    @Query(value = "SELECT * FROM chart_scores c " +
            "WHERE (:genreId IS NULL OR JSON_CONTAINS(c.genre_ids, JSON_OBJECT('id', :genreId))) " +
            "AND (:descriptorId IS NULL OR JSON_CONTAINS(c.descriptor_ids, CAST(:descriptorId AS CHAR))) " +
            "AND (:locationId IS NULL OR c.location_id = :locationId) " +
            "AND (:language IS NULL OR JSON_CONTAINS(c.languages, CONCAT('\"', :language, '\"'))) " +
            "AND (:year IS NULL OR c.release_year = :year) " +
            "AND (:releaseType IS NULL OR c.release_type = :releaseType) " +
            "AND (:includeEsoteric = true OR c.is_esoteric = false) " +
            "ORDER BY c.bayesian_score DESC",
            countQuery = "SELECT count(*) FROM chart_scores c " +
            "WHERE (:genreId IS NULL OR JSON_CONTAINS(c.genre_ids, JSON_OBJECT('id', :genreId))) " +
            "AND (:descriptorId IS NULL OR JSON_CONTAINS(c.descriptor_ids, CAST(:descriptorId AS CHAR))) " +
            "AND (:locationId IS NULL OR c.location_id = :locationId) " +
            "AND (:language IS NULL OR JSON_CONTAINS(c.languages, CONCAT('\"', :language, '\"'))) " +
            "AND (:year IS NULL OR c.release_year = :year) " +
            "AND (:releaseType IS NULL OR c.release_type = :releaseType) " +
            "AND (:includeEsoteric = true OR c.is_esoteric = false)",
            nativeQuery = true)
    List<ChartScore> findCharts(@Param("genreId") Long genreId,
                                @Param("descriptorId") Long descriptorId,
                                @Param("locationId") Long locationId,
                                @Param("language") String language,
                                @Param("year") Integer year,
                                @Param("releaseType") String releaseType,
                                @Param("includeEsoteric") Boolean includeEsoteric,
                                Pageable pageable);
    */
}
