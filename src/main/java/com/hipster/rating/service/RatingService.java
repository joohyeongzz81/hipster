package com.hipster.rating.service;

import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.NotFoundException;
import com.hipster.rating.domain.Rating;
import com.hipster.rating.dto.request.CreateRatingRequest;
import com.hipster.rating.dto.response.RatingResponse;
import com.hipster.rating.dto.response.RatingResult;
import com.hipster.rating.repository.RatingRepository;
import com.hipster.release.domain.Release;
import com.hipster.release.domain.ReleaseStatus;
import com.hipster.release.repository.ReleaseRepository;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RatingService {

    private final RatingRepository ratingRepository;
    private final UserRepository userRepository;
    private final ReleaseRepository releaseRepository;

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

        final Rating rating = existingRating.map(r -> {
            r.updateScore(request.score(), user.getWeightingScore());
            return r;
        }).orElseGet(() -> Rating.builder()
                .userId(userId)
                .releaseId(releaseId)
                .score(request.score())
                .userWeightingScore(user.getWeightingScore())
                .build());

        ratingRepository.save(rating);

        userRepository.updateLastActiveDate(userId, LocalDateTime.now());

        final RatingResponse response = RatingResponse.from(rating, user.getUsername());
        return new RatingResult(response, isCreated);
    }
}
