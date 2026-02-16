package com.hipster.user.service;

import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.ForbiddenException;
import com.hipster.global.exception.NotFoundException;
import com.hipster.user.domain.User;
import com.hipster.user.dto.UserProfileResponse;
import com.hipster.user.dto.WeightingResponse;
import com.hipster.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserProfileResponse getUserProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));
        return UserProfileResponse.from(user);
    }

    public WeightingResponse getUserWeighting(Long userId, Long requestUserId) {
        if (!userId.equals(requestUserId)) {
            throw new ForbiddenException(ErrorCode.ACCESS_DENIED);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));

        return WeightingResponse.from(user);
    }
}
