package com.hipster.auth.dto;

import com.hipster.user.domain.User;
import java.time.LocalDateTime;

public record UserProfileDto(
        Long id,
        String username,
        Double weightingScore,
        Integer totalRatings,
        Integer totalReviews,
        LocalDateTime createdAt
) {
    public static UserProfileDto from(User user) {
        return new UserProfileDto(
                user.getId(),
                user.getUsername(),
                user.getWeightingScore(),
                0, // TODO: Implement total ratings count
                0, // TODO: Implement total reviews count
                user.getCreatedAt()
        );
    }
}
