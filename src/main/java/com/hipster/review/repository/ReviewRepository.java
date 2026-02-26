package com.hipster.review.repository;

import com.hipster.review.domain.Review;
import com.hipster.review.domain.ReviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {

    List<Review> findByUserId(Long userId);

    List<Review> findByUserIdIn(List<Long> userIds);

    Page<Review> findByReleaseIdAndStatus(Long releaseId, ReviewStatus status, Pageable pageable);

    Page<Review> findByUserIdAndStatus(Long userId, ReviewStatus status, Pageable pageable);

    Optional<Review> findByIdAndStatus(Long id, ReviewStatus status);

    void deleteByUserId(Long userId);
}
