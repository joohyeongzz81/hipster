package com.hipster.user.service;

import com.hipster.global.exception.BadRequestException;
import com.hipster.global.exception.ConflictException;
import com.hipster.global.exception.ErrorCode;
import com.hipster.user.domain.User;
import com.hipster.user.domain.UserWeightStats;
import com.hipster.user.dto.request.ChangePasswordRequest;
import com.hipster.user.dto.request.DeleteAccountRequest;
import com.hipster.user.dto.request.UpdateProfileRequest;
import com.hipster.user.dto.response.WeightingResponse;
import com.hipster.user.repository.UserRepository;
import com.hipster.user.repository.UserWeightStatsRepository;
import com.hipster.rating.repository.RatingRepository;
import com.hipster.review.repository.ReviewRepository;
import com.hipster.moderation.repository.ModerationQueueRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @InjectMocks
    private UserService userService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RatingRepository ratingRepository;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ModerationQueueRepository moderationQueueRepository;

    @Mock
    private UserWeightStatsRepository userWeightStatsRepository;

    @Test
    @DisplayName("유저 가중치 조회는 user_weight_stats 기준 설명 값을 반환한다")
    void getUserWeighting_ReturnsStatsBackedResponse() {
        Long userId = 1L;
        User user = User.builder().username("name").email("test@test.com").passwordHash("hash").build();
        setField(user, "id", userId);
        setField(user, "weightingScore", 0.87);
        setField(user, "updatedAt", LocalDateTime.of(2026, 3, 1, 10, 0));

        UserWeightStats stats = UserWeightStats.builder()
                .userId(userId)
                .ratingCount(20L)
                .ratingVariance(1.2)
                .reviewCount(3L)
                .reviewAvgLength(150.0)
                .lastActiveDate(LocalDateTime.of(2026, 2, 28, 9, 0))
                .build();
        setField(stats, "lastCalculatedAt", LocalDateTime.of(2026, 3, 15, 2, 0));

        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(userWeightStatsRepository.findById(userId)).willReturn(Optional.of(stats));

        WeightingResponse response = userService.getUserWeighting(userId, userId);

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.weightingScore()).isEqualTo(0.87);
        assertThat(response.reviewBonus()).isTrue();
        assertThat(response.lastCalculated()).isEqualTo(LocalDateTime.of(2026, 3, 15, 2, 0));
    }

    @Test
    @DisplayName("유저 가중치 조회는 stats 가 없으면 설명 값을 비워 둔다")
    void getUserWeighting_WithoutStats_ReturnsFallbackResponse() {
        Long userId = 1L;
        User user = User.builder().username("name").email("test@test.com").passwordHash("hash").build();
        setField(user, "id", userId);
        setField(user, "weightingScore", 0.42);

        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(userWeightStatsRepository.findById(userId)).willReturn(Optional.empty());

        WeightingResponse response = userService.getUserWeighting(userId, userId);

        assertThat(response.reviewBonus()).isFalse();
        assertThat(response.lastCalculated()).isNull();
    }

    @Test
    @DisplayName("회원 프로필(닉네임) 수정 - 성공")
    void updateProfile_Success() {
        // given
        Long userId = 1L;
        User user = User.builder().username("oldName").email("test@test.com").passwordHash("hash").build();
        UpdateProfileRequest request = new UpdateProfileRequest("newName");

        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(userRepository.existsByUsername("newName")).willReturn(false);

        // when
        userService.updateProfile(userId, request);

        // then
        assertThat(user.getUsername()).isEqualTo("newName");
    }

    @Test
    @DisplayName("회원 프로필 수정 - 중복 닉네임 실패")
    void updateProfile_DuplicateUsername() {
        // given
        Long userId = 1L;
        User user = User.builder().username("oldName").email("test@test.com").passwordHash("hash").build();
        UpdateProfileRequest request = new UpdateProfileRequest("duplicateName");

        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(userRepository.existsByUsername("duplicateName")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> userService.updateProfile(userId, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(ErrorCode.USERNAME_ALREADY_EXISTS.getMessage());
    }

    @Test
    @DisplayName("비밀번호 변경 - 성공")
    void changePassword_Success() {
        // given
        Long userId = 1L;
        User user = User.builder().username("name").email("test@test.com").passwordHash("oldHash").build();
        ChangePasswordRequest request = new ChangePasswordRequest("oldPw", "newPw");

        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(passwordEncoder.matches("oldPw", "oldHash")).willReturn(true);
        given(passwordEncoder.encode("newPw")).willReturn("newHash");

        // when
        userService.changePassword(userId, request);

        // then
        assertThat(user.getPasswordHash()).isEqualTo("newHash");
    }

    @Test
    @DisplayName("비밀번호 변경 - 기존 비밀번호 불일치 실패")
    void changePassword_InvalidCurrentPassword() {
        // given
        Long userId = 1L;
        User user = User.builder().username("name").email("test@test.com").passwordHash("oldHash").build();
        ChangePasswordRequest request = new ChangePasswordRequest("wrongPw", "newPw");

        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(passwordEncoder.matches("wrongPw", "oldHash")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> userService.changePassword(userId, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(ErrorCode.INVALID_PASSWORD.getMessage());
    }

    @Test
    @DisplayName("회원 탈퇴 - 성공")
    void deleteAccount_Success() {
        // given
        Long userId = 1L;
        User user = User.builder().username("name").email("test@test.com").passwordHash("hash").build();
        DeleteAccountRequest request = new DeleteAccountRequest("pw");

        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(passwordEncoder.matches("pw", "hash")).willReturn(true);

        // when
        userService.deleteAccount(userId, request);

        // then
        verify(ratingRepository).deleteByUserId(userId);
        verify(reviewRepository).deleteByUserId(userId);
        verify(moderationQueueRepository).deleteBySubmitterId(userId);
        verify(userWeightStatsRepository).deleteByUserId(userId);
        verify(userRepository).delete(user);
    }

    private void setField(final Object target, final String fieldName, final Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
