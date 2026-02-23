package com.hipster.batch.processor;

import com.hipster.batch.WeightingService;
import com.hipster.rating.domain.Rating;
import com.hipster.rating.repository.RatingRepository;
import com.hipster.review.domain.Review;
import com.hipster.review.repository.ReviewRepository;
import com.hipster.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 단건 User를 받아 가중치를 계산하고 User에 반영.
 * Batch Fetch(IN절)는 Writer에서 청크 단위로 처리하므로,
 * 여기서는 개별 조회로 처리 (DB 부하 최소화를 원하면 Writer로 이동 가능).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeightingItemProcessor implements ItemProcessor<User, User> {

    private final WeightingService weightingService;
    private final RatingRepository ratingRepository;
    private final ReviewRepository reviewRepository;

    @Override
    public User process(final User user) {
        final List<Rating> ratings = ratingRepository.findByUserId(user.getId());
        final List<Review> reviews = reviewRepository.findByUserId(user.getId());

        final double newWeight = weightingService.calculateUserWeightingForBatch(user, ratings, reviews);
        user.updateWeightingScore(newWeight);

        return user;
    }
}
