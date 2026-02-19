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
}
