package com.hipster.chart.repository;

import com.hipster.chart.domain.ChartScore;
import com.hipster.global.domain.Language;
import com.hipster.release.domain.ReleaseType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChartScoreRepository extends JpaRepository<ChartScore, Long> {

    Optional<ChartScore> findByRelease_Id(Long releaseId);

    List<ChartScore> findTop100ByOrderByBayesianScoreDesc();

    List<ChartScore> findAllByOrderByBayesianScoreDesc(Pageable pageable);

    List<ChartScore> findTopNByOrderByBayesianScoreDesc(Pageable pageable);

    Optional<ChartScore> findFirstByOrderByLastUpdatedDesc();

    @Query("SELECT c FROM ChartScore c " +
            "JOIN c.release r " +
            "LEFT JOIN r.releaseGenres rg " +
            "LEFT JOIN r.releaseDescriptors rd " +
            "LEFT JOIN r.releaseLanguages rl " +
            "WHERE (:genreId IS NULL OR rg.genre.id = :genreId) " +
            "AND (:descriptorId IS NULL OR rd.descriptor.id = :descriptorId) " +
            "AND (:locationId IS NULL OR r.locationId = :locationId) " +
            "AND (:language IS NULL OR rl.language = :language) " +
            "AND (:year IS NULL OR YEAR(r.releaseDate) = :year) " +
            "AND (:releaseType IS NULL OR r.releaseType = :releaseType) " +
            "AND (:includeEsoteric = true OR c.isEsoteric = false) " +
            "ORDER BY c.bayesianScore DESC")
    List<ChartScore> findCharts(@Param("genreId") Long genreId,
                                @Param("descriptorId") Long descriptorId,
                                @Param("locationId") Long locationId,
                                @Param("language") Language language,
                                @Param("year") Integer year,
                                @Param("releaseType") ReleaseType releaseType,
                                @Param("includeEsoteric") Boolean includeEsoteric,
                                Pageable pageable);
}
