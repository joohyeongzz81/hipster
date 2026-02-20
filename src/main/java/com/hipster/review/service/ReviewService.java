package com.hipster.review.service;

import com.hipster.global.dto.PagedResponse;
import com.hipster.global.dto.PaginationDto;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.ForbiddenException;
import com.hipster.global.exception.NotFoundException;
import com.hipster.review.domain.Review;
import com.hipster.review.domain.ReviewStatus;
import com.hipster.review.dto.CreateReviewRequest;
import com.hipster.review.dto.ReviewResponse;
import com.hipster.review.dto.UpdateReviewRequest;
import com.hipster.review.repository.ReviewRepository;
import com.hipster.release.repository.ReleaseRepository;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ReleaseRepository releaseRepository;
    private final UserRepository userRepository;

    @Transactional
    public ReviewResponse createReview(final Long releaseId, final CreateReviewRequest request, final Long userId) {
        validateReleaseExists(releaseId);

        final User user = findUserOrThrow(userId);

        final Review review = reviewRepository.save(Review.builder()
                .userId(userId)
                .releaseId(releaseId)
                .content(request.content())
                .build());

        return ReviewResponse.of(review, user.getUsername());
    }

    public PagedResponse<ReviewResponse> getReviewsByRelease(final Long releaseId, final int page, final int limit) {
        validateReleaseExists(releaseId);

        final Pageable pageable = PageRequest.of(Math.max(0, page - 1), limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        final Page<Review> pageResult = reviewRepository.findByReleaseIdAndStatus(releaseId, ReviewStatus.ACTIVE, pageable);

        final Map<Long, User> userMap = buildUserMap(pageResult.getContent());

        final List<ReviewResponse> content = pageResult.getContent().stream()
                .map(review -> ReviewResponse.of(review, resolveUsername(userMap, review.getUserId())))
                .toList();

        final PaginationDto pagination = new PaginationDto(page, limit, pageResult.getTotalElements(), pageResult.getTotalPages());

        return new PagedResponse<>(content, pagination);
    }

    public ReviewResponse getReview(final Long reviewId) {
        final Review review = findActiveReviewOrThrow(reviewId);
        final String username = userRepository.findById(review.getUserId())
                .map(User::getUsername)
                .orElse("Unknown");

        return ReviewResponse.of(review, username);
    }

    @Transactional
    public ReviewResponse updateReview(final Long reviewId, final UpdateReviewRequest request, final Long userId) {
        final Review review = findActiveReviewOrThrow(reviewId);
        validateOwnership(review, userId);

        review.updateContent(request.content());
        reviewRepository.save(review);

        final String username = userRepository.findById(userId)
                .map(User::getUsername)
                .orElse("Unknown");

        return ReviewResponse.of(review, username);
    }

    @Transactional
    public void deleteReview(final Long reviewId, final Long userId) {
        final Review review = findActiveReviewOrThrow(reviewId);
        validateOwnership(review, userId);

        review.delete();
        reviewRepository.save(review);
    }

    private Review findActiveReviewOrThrow(final Long reviewId) {
        return reviewRepository.findByIdAndStatus(reviewId, ReviewStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException(ErrorCode.REVIEW_NOT_FOUND));
    }

    private void validateOwnership(final Review review, final Long userId) {
        if (!review.getUserId().equals(userId)) {
            throw new ForbiddenException(ErrorCode.REVIEW_NOT_OWNER);
        }
    }

    private void validateReleaseExists(final Long releaseId) {
        if (!releaseRepository.existsById(releaseId)) {
            throw new NotFoundException(ErrorCode.RELEASE_NOT_FOUND);
        }
    }

    private User findUserOrThrow(final Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));
    }

    private Map<Long, User> buildUserMap(final List<Review> reviews) {
        final Set<Long> userIds = reviews.stream()
                .map(Review::getUserId)
                .collect(Collectors.toSet());
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private String resolveUsername(final Map<Long, User> userMap, final Long userId) {
        final User user = userMap.get(userId);
        return user != null ? user.getUsername() : "Unknown";
    }
}
