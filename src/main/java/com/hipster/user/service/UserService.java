package com.hipster.user.service;

import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.ForbiddenException;
import com.hipster.global.exception.NotFoundException;
import com.hipster.user.domain.User;
import com.hipster.user.dto.response.UserProfileResponse;
import com.hipster.user.dto.response.WeightingResponse;
import com.hipster.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserProfileResponse getUserProfile(final Long userId) {
        final User user = findUserOrThrow(userId);
        return UserProfileResponse.from(user);
    }

    public WeightingResponse getUserWeighting(final Long userId, final Long requestUserId) {
        if (!userId.equals(requestUserId)) {
            throw new ForbiddenException(ErrorCode.ACCESS_DENIED);
        }

        final User user = findUserOrThrow(userId);
        return WeightingResponse.from(user);
    }

    private User findUserOrThrow(final Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));
    }
}
