package com.hipster.user.dto.response;

import com.hipster.user.domain.User;
import java.time.LocalDateTime;

public record UserProfileResponse(
        Long id,
        String username,
        Double weightingScore,
        Integer totalRatings,
        Integer totalReviews,
        LocalDateTime createdAt
) {
    public static UserProfileResponse from(final User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getWeightingScore(),
                0, // TODO: Implement total ratings count
                0, // TODO: Implement total reviews count
                user.getCreatedAt()
        );
    }
}
