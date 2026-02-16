package com.hipster.release.repository;

import com.hipster.release.domain.Release;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public interface ReleaseRepository extends JpaRepository<Release, Long>, JpaSpecificationExecutor<Release> {

    Page<Release> findByTitleContainingIgnoreCase(String title, Pageable pageable);

    Page<Release> findByPendingApprovalFalse(Pageable pageable);

    boolean existsByTitleAndArtistIdAndReleaseDate(String title, Long artistId, LocalDate releaseDate);
}
