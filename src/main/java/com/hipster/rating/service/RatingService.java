package com.hipster.rating.service;

import com.hipster.artist.domain.Artist;
import com.hipster.artist.repository.ArtistRepository;
import com.hipster.global.dto.response.PagedResponse;
import com.hipster.global.dto.response.PaginationDto;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.NotFoundException;
import com.hipster.rating.domain.Rating;
import com.hipster.rating.domain.ReleaseRatingSummary;
import com.hipster.rating.dto.request.CreateRatingRequest;
import com.hipster.rating.dto.response.RatingResponse;
import com.hipster.rating.dto.response.RatingResult;
import com.hipster.rating.dto.response.UserRatingResponse;
import com.hipster.rating.repository.RatingRepository;
import com.hipster.rating.repository.ReleaseRatingSummaryRepository;
import com.hipster.release.domain.Release;
import com.hipster.release.domain.ReleaseStatus;
import com.hipster.release.repository.ReleaseRepository;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RatingService {

    private final RatingRepository ratingRepository;
    private final UserRepository userRepository;
    private final ReleaseRepository releaseRepository;
    private final ArtistRepository artistRepository;
    private final ReleaseRatingSummaryRepository releaseRatingSummaryRepository;

    @Transactional
    public RatingResult createOrUpdateRating(final Long releaseId, final CreateRatingRequest request, final Long userId) {
        final Release release = releaseRepository.findById(releaseId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RELEASE_NOT_FOUND));

        if (release.getStatus() != ReleaseStatus.ACTIVE) {
            throw new NotFoundException(ErrorCode.RELEASE_NOT_FOUND);
        }

        final User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));

        final Optional<Rating> existingRating = ratingRepository.findByUserIdAndReleaseId(userId, releaseId);
        final boolean isCreated = existingRating.isEmpty();
        final double oldScore = existingRating.map(Rating::getScore).orElse(0.0);

        final Rating rating = existingRating.map(r -> {
            r.updateScore(request.score());
            return r;
        }).orElseGet(() -> Rating.builder()
                .userId(userId)
                .releaseId(releaseId)
                .score(request.score())
                .build());

        ratingRepository.save(rating);

        userRepository.updateLastActiveDate(userId, LocalDateTime.now());

        if (isCreated) {
            releaseRatingSummaryRepository.incrementRating(releaseId, request.score());
        } else if (oldScore != request.score()) {
            releaseRatingSummaryRepository.updateRatingScore(releaseId, oldScore, request.score());
        }

        final RatingResponse response = RatingResponse.from(rating, user.getUsername());
        return new RatingResult(response, isCreated);
    }

    @Transactional(readOnly = true)
    public PagedResponse<UserRatingResponse> getUserRatings(final Long targetUserId, final int page, final int limit) {
        if (!userRepository.existsById(targetUserId)) {
            throw new NotFoundException(ErrorCode.TARGET_USER_NOT_FOUND);
        }

        final Pageable pageable = PageRequest.of(Math.max(0, page - 1), limit);
        final Page<Rating> pageResult = ratingRepository.findByUserIdOrderByCreatedAtDesc(targetUserId, pageable);

        final Set<Long> releaseIds = pageResult.getContent().stream()
                .map(Rating::getReleaseId)
                .collect(Collectors.toSet());

        final Map<Long, Release> releaseMap = releaseRepository.findAllById(releaseIds).stream()
                .collect(Collectors.toMap(Release::getId, Function.identity()));

        final Set<Long> artistIds = releaseMap.values().stream()
                .map(Release::getArtistId)
                .collect(Collectors.toSet());

        final Map<Long, Artist> artistMap = artistRepository.findAllById(artistIds).stream()
                .collect(Collectors.toMap(Artist::getId, Function.identity()));

        final List<UserRatingResponse> content = pageResult.getContent().stream()
                .map(rating -> {
                    final Release release = releaseMap.get(rating.getReleaseId());
                    final String releaseTitle = release != null ? release.getTitle() : "Unknown Release";
                    final Artist artist = release != null ? artistMap.get(release.getArtistId()) : null;
                    final String artistName = artist != null ? artist.getName() : "Unknown Artist";

                    return new UserRatingResponse(
                            rating.getId(),
                            rating.getReleaseId(),
                            releaseTitle,
                            artistName,
                            rating.getScore(),
                            rating.getCreatedAt(),
                            rating.getUpdatedAt()
                    );
                })
                .toList();

        final PaginationDto pagination = new PaginationDto(page, limit, pageResult.getTotalElements(), pageResult.getTotalPages());

        return new PagedResponse<>(content, pagination);
    }
}
