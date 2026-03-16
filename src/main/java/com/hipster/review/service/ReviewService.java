package com.hipster.review.service;

import com.hipster.global.dto.response.PagedResponse;
import com.hipster.global.dto.response.PaginationDto;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.ForbiddenException;
import com.hipster.global.exception.NotFoundException;
import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.dto.request.ModerationSubmitRequest;
import com.hipster.moderation.service.ModerationQueueService;
import com.hipster.review.domain.Review;
import com.hipster.review.domain.ReviewStatus;
import com.hipster.review.dto.request.CreateReviewRequest;
import com.hipster.review.dto.response.ReviewResponse;
import com.hipster.review.dto.request.UpdateReviewRequest;
import com.hipster.review.repository.ReviewRepository;
import com.hipster.release.domain.Release;
import com.hipster.release.domain.ReleaseStatus;
import com.hipster.release.repository.ReleaseRepository;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import com.hipster.artist.domain.Artist;
import com.hipster.artist.repository.ArtistRepository;
import com.hipster.review.dto.response.UserReviewResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
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
    private final ArtistRepository artistRepository;
    private final ModerationQueueService moderationQueueService;

    @Transactional
    public ReviewResponse createReview(final Long releaseId, final CreateReviewRequest request, final Long userId) {
        validateActiveRelease(releaseId);

        final User user = findUserOrThrow(userId);
        final boolean requestedPublication = Boolean.TRUE.equals(request.isPublished());

        final Review review = reviewRepository.save(Review.builder()
                .userId(userId)
                .releaseId(releaseId)
                .content(request.content())
                .isPublished(false)
                .build());

        if (requestedPublication) {
            submitReviewPublication(review, userId, "create_review");
        }

        return ReviewResponse.of(review, user.getUsername());
    }

    public PagedResponse<ReviewResponse> getReviewsByRelease(final Long releaseId, final int page, final int limit) {
        validateActiveRelease(releaseId);

        final Pageable pageable = PageRequest.of(Math.max(0, page - 1), limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        final Page<Review> pageResult = reviewRepository.findByReleaseIdAndStatusAndIsPublishedTrue(
                releaseId, ReviewStatus.ACTIVE, pageable);

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

        final boolean shouldQueuePublication = shouldQueuePublication(review.getIsPublished(), request.isPublished());

        if (shouldQueuePublication) {
            review.update(request.content(), false);
        } else {
            review.update(request.content(), request.isPublished());
        }
        reviewRepository.save(review);

        if (shouldQueuePublication) {
            submitReviewPublication(review, userId, "update_review");
        }

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

    @Transactional
    public ReviewResponse updateReviewPublication(final Long reviewId, final boolean isPublished, final Long userId) {
        final Review review = findActiveReviewOrThrow(reviewId);
        validateOwnership(review, userId);

        if (isPublished && review.getIsPublished()) {
            final String username = userRepository.findById(userId)
                    .map(User::getUsername)
                    .orElse("Unknown");
            return ReviewResponse.of(review, username);
        }

        review.unpublish();
        reviewRepository.save(review);

        if (isPublished) {
            submitReviewPublication(review, userId, "publish_review");
        }

        final String username = userRepository.findById(userId)
                .map(User::getUsername)
                .orElse("Unknown");

        return ReviewResponse.of(review, username);
    }

    public PagedResponse<UserReviewResponse> getUserReviews(final Long targetUserId, final int page, final int limit) {
        if (!userRepository.existsById(targetUserId)) {
            throw new NotFoundException(ErrorCode.TARGET_USER_NOT_FOUND);
        }

        final Pageable pageable = PageRequest.of(Math.max(0, page - 1), limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        final Page<Review> pageResult = reviewRepository.findByUserIdAndStatus(targetUserId, ReviewStatus.ACTIVE, pageable);

        final Set<Long> releaseIds = pageResult.getContent().stream()
                .map(Review::getReleaseId)
                .collect(Collectors.toSet());

        final Map<Long, Release> releaseMap = releaseRepository.findAllById(releaseIds).stream()
                .collect(Collectors.toMap(Release::getId, Function.identity()));

        final Set<Long> artistIds = releaseMap.values().stream()
                .map(Release::getArtistId)
                .collect(Collectors.toSet());

        final Map<Long, Artist> artistMap = artistRepository.findAllById(artistIds).stream()
                .collect(Collectors.toMap(Artist::getId, Function.identity()));

        final List<UserReviewResponse> content = pageResult.getContent().stream()
                .map(review -> {
                    final Release release = releaseMap.get(review.getReleaseId());
                    final String releaseTitle = release != null ? release.getTitle() : "Unknown Release";
                    final Artist artist = release != null ? artistMap.get(release.getArtistId()) : null;
                    final String artistName = artist != null ? artist.getName() : "Unknown Artist";

                    return new UserReviewResponse(
                            review.getId(),
                            review.getReleaseId(),
                            releaseTitle,
                            artistName,
                            review.getContent(),
                            review.getIsPublished(),
                            review.getCreatedAt(),
                            review.getUpdatedAt()
                    );
                })
                .toList();

        final PaginationDto pagination = new PaginationDto(page, limit, pageResult.getTotalElements(), pageResult.getTotalPages());

        return new PagedResponse<>(content, pagination);
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

    private void validateActiveRelease(final Long releaseId) {
        final Release release = releaseRepository.findById(releaseId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RELEASE_NOT_FOUND));
        if (release.getStatus() != ReleaseStatus.ACTIVE) {
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

    private boolean shouldQueuePublication(final boolean currentPublished, final Boolean requestedPublished) {
        return Boolean.TRUE.equals(requestedPublished) || (requestedPublished == null && currentPublished);
    }

    private void submitReviewPublication(final Review review, final Long userId, final String source) {
        moderationQueueService.submit(new ModerationSubmitRequest(
                EntityType.REVIEW,
                review.getId(),
                buildReviewMetaComment(review, source),
                buildReviewSnapshot(review, source)
        ), userId);
    }

    private String buildReviewMetaComment(final Review review, final String source) {
        return "Review publication request (" + source + ") for review " + review.getId() + ".";
    }

    private Map<String, Object> buildReviewSnapshot(final Review review, final String source) {
        final Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("reviewId", review.getId());
        snapshot.put("releaseId", review.getReleaseId());
        snapshot.put("userId", review.getUserId());
        snapshot.put("content", review.getContent());
        snapshot.put("requestedPublication", true);
        snapshot.put("source", source);
        return snapshot;
    }
}
