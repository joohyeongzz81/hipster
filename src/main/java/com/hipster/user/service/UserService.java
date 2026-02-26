package com.hipster.user.service;

import com.hipster.global.exception.BadRequestException;
import com.hipster.global.exception.ConflictException;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.ForbiddenException;
import com.hipster.global.exception.NotFoundException;
import com.hipster.moderation.repository.ModerationQueueRepository;
import com.hipster.rating.repository.RatingRepository;
import com.hipster.review.repository.ReviewRepository;
import com.hipster.user.domain.User;
import com.hipster.user.dto.request.ChangePasswordRequest;
import com.hipster.user.dto.request.DeleteAccountRequest;
import com.hipster.user.dto.request.UpdateProfileRequest;
import com.hipster.user.dto.response.UserProfileResponse;
import com.hipster.user.dto.response.WeightingResponse;
import com.hipster.user.repository.UserRepository;
import com.hipster.user.repository.UserWeightStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RatingRepository ratingRepository;
    private final ReviewRepository reviewRepository;
    private final ModerationQueueRepository moderationQueueRepository;
    private final UserWeightStatsRepository userWeightStatsRepository;

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

    @Transactional
    public void updateProfile(final Long userId, final UpdateProfileRequest request) {
        final User user = findUserOrThrow(userId);

        if (!user.getUsername().equals(request.username()) && userRepository.existsByUsername(request.username())) {
            throw new ConflictException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }

        user.updateUsername(request.username());
    }

    @Transactional
    public void changePassword(final Long userId, final ChangePasswordRequest request) {
        final User user = findUserOrThrow(userId);

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BadRequestException(ErrorCode.INVALID_PASSWORD);
        }

        user.changePassword(passwordEncoder.encode(request.newPassword()));
    }

    @Transactional
    public void deleteAccount(final Long userId, final DeleteAccountRequest request) {
        final User user = findUserOrThrow(userId);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadRequestException(ErrorCode.INVALID_PASSWORD);
        }

        // 1. 연관 데이터 일괄 삭제 (소프트 삭제 대신 개인정보보호를 위한 하드 삭제 정책 적용시)
        ratingRepository.deleteByUserId(userId);
        reviewRepository.deleteByUserId(userId);
        moderationQueueRepository.deleteBySubmitterId(userId);
        userWeightStatsRepository.deleteByUserId(userId);

        // 2. 유저 엔티티 삭제
        userRepository.delete(user);
    }

    private User findUserOrThrow(final Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));
    }
}
