package com.hipster.chart.repository;

import com.hipster.chart.domain.ChartScore;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChartScoreRepository extends JpaRepository<ChartScore, Long> {

    Optional<ChartScore> findByReleaseId(Long releaseId);

    List<ChartScore> findTop100ByOrderByBayesianScoreDesc();

    List<ChartScore> findAllByOrderByBayesianScoreDesc(Pageable pageable);

    List<ChartScore> findTopNByOrderByBayesianScoreDesc(Pageable pageable);

    Optional<ChartScore> findFirstByOrderByLastUpdatedDesc();

    @org.springframework.data.jpa.repository.Query("SELECT c FROM ChartScore c, Release r WHERE c.releaseId = r.id " +
            "AND (:genreId IS NULL OR r.genreId = :genreId) " +
            "AND (:year IS NULL OR YEAR(r.releaseDate) = :year) " +
            "AND (:releaseType IS NULL OR r.releaseType = :releaseType) " +
            "AND (:includeEsoteric = true OR c.isEsoteric = false) " +
            "ORDER BY c.bayesianScore DESC")
    List<ChartScore> findCharts(@org.springframework.data.repository.query.Param("genreId") Long genreId,
                                @org.springframework.data.repository.query.Param("year") Integer year,
                                @org.springframework.data.repository.query.Param("releaseType") com.hipster.release.domain.ReleaseType releaseType,
                                @org.springframework.data.repository.query.Param("includeEsoteric") Boolean includeEsoteric,
                                Pageable pageable);
}
