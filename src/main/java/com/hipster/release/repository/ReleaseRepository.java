package com.hipster.release.repository;

import com.hipster.release.domain.Release;
import com.hipster.release.domain.ReleaseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface ReleaseRepository extends JpaRepository<Release, Long>, JpaSpecificationExecutor<Release> {

    Page<Release> findByTitleContainingIgnoreCase(String title, Pageable pageable);

    Page<Release> findByStatus(ReleaseStatus status, Pageable pageable);

    List<Release> findAllByStatus(ReleaseStatus status);

    boolean existsByTitleAndArtistIdAndReleaseDate(String title, Long artistId, LocalDate releaseDate);
}
