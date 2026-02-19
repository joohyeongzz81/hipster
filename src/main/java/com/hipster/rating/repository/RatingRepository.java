package com.hipster.rating.repository;

import com.hipster.rating.domain.Rating;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RatingRepository extends JpaRepository<Rating, Long> {

    Optional<Rating> findByUserIdAndReleaseId(Long userId, Long releaseId);

    boolean existsByUserIdAndReleaseId(Long userId, Long releaseId);

    Page<Rating> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<Rating> findByReleaseIdOrderByCreatedAtDesc(Long releaseId, Pageable pageable);

    List<Rating> findByUserId(Long userId);
}
