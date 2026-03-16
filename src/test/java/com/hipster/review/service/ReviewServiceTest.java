package com.hipster.review.service;

import com.hipster.artist.domain.Artist;
import com.hipster.artist.repository.ArtistRepository;
import com.hipster.global.dto.response.PagedResponse;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.NotFoundException;
import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.domain.ModerationStatus;
import com.hipster.moderation.dto.request.ModerationSubmitRequest;
import com.hipster.moderation.dto.response.ModerationSubmitResponse;
import com.hipster.moderation.service.ModerationQueueService;
import com.hipster.release.domain.Release;
import com.hipster.release.domain.ReleaseStatus;
import com.hipster.release.domain.ReleaseType;
import com.hipster.release.repository.ReleaseRepository;
import com.hipster.review.domain.Review;
import com.hipster.review.domain.ReviewStatus;
import com.hipster.review.dto.request.CreateReviewRequest;
import com.hipster.review.dto.request.UpdateReviewRequest;
import com.hipster.review.dto.response.ReviewResponse;
import com.hipster.review.dto.response.UserReviewResponse;
import com.hipster.review.repository.ReviewRepository;
import com.hipster.user.domain.User;
import com.hipster.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @InjectMocks
    private ReviewService reviewService;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ReleaseRepository releaseRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ArtistRepository artistRepository;

    @Mock
    private ModerationQueueService moderationQueueService;

    @Test
    @DisplayName("사용자 공개 요청 리뷰 생성은 unpublished 로 저장하고 moderation 큐에 등록한다")
    void createReview_PublishRequested_QueuesModeration() {
        Long releaseId = 100L;
        Long userId = 1L;
        User user = user(userId, "reviewer");
        Release release = activeRelease(releaseId, 200L, "Test Title");

        given(releaseRepository.findById(releaseId)).willReturn(Optional.of(release));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(reviewRepository.save(any(Review.class))).willAnswer(invocation -> {
            Review review = invocation.getArgument(0);
            setField(review, "id", 900L);
            return review;
        });
        given(moderationQueueService.submit(any(ModerationSubmitRequest.class), eq(userId)))
                .willReturn(new ModerationSubmitResponse(1L, ModerationStatus.PENDING, "queued", "14 days"));

        ReviewResponse response = reviewService.createReview(releaseId, new CreateReviewRequest("A".repeat(60), true), userId);

        assertThat(response.isPublished()).isFalse();

        ArgumentCaptor<ModerationSubmitRequest> captor = ArgumentCaptor.forClass(ModerationSubmitRequest.class);
        verify(moderationQueueService).submit(captor.capture(), eq(userId));
        assertThat(captor.getValue().entityType()).isEqualTo(EntityType.REVIEW);
        assertThat(captor.getValue().entityId()).isEqualTo(900L);
        assertThat(captor.getValue().submissionSnapshot()).isNotNull();
    }

    @Test
    @DisplayName("공개 요청된 리뷰 발행 API 는 즉시 공개하지 않고 moderation 큐에 등록한다")
    void updateReviewPublication_PublishRequested_QueuesModeration() {
        Long reviewId = 901L;
        Long userId = 1L;
        User user = user(userId, "reviewer");
        Review review = review(reviewId, userId, 100L, false, "B".repeat(60));

        given(reviewRepository.findByIdAndStatus(reviewId, ReviewStatus.ACTIVE)).willReturn(Optional.of(review));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(moderationQueueService.submit(any(ModerationSubmitRequest.class), eq(userId)))
                .willReturn(new ModerationSubmitResponse(2L, ModerationStatus.PENDING, "queued", "14 days"));

        ReviewResponse response = reviewService.updateReviewPublication(reviewId, true, userId);

        assertThat(response.isPublished()).isFalse();
        assertThat(review.getIsPublished()).isFalse();

        ArgumentCaptor<ModerationSubmitRequest> captor = ArgumentCaptor.forClass(ModerationSubmitRequest.class);
        verify(moderationQueueService).submit(captor.capture(), eq(userId));
        assertThat(captor.getValue().entityType()).isEqualTo(EntityType.REVIEW);
        assertThat(captor.getValue().entityId()).isEqualTo(reviewId);
    }

    @Test
    @DisplayName("이미 공개된 리뷰를 수정하면 다시 unpublished 로 전환하고 moderation 큐에 등록한다")
    void updateReview_PublishedReviewContentChanged_QueuesModeration() {
        Long reviewId = 902L;
        Long userId = 1L;
        User user = user(userId, "reviewer");
        Review review = review(reviewId, userId, 100L, true, "C".repeat(60));

        given(reviewRepository.findByIdAndStatus(reviewId, ReviewStatus.ACTIVE)).willReturn(Optional.of(review));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(moderationQueueService.submit(any(ModerationSubmitRequest.class), eq(userId)))
                .willReturn(new ModerationSubmitResponse(3L, ModerationStatus.PENDING, "queued", "14 days"));

        ReviewResponse response = reviewService.updateReview(reviewId, new UpdateReviewRequest("D".repeat(70), null), userId);

        assertThat(response.isPublished()).isFalse();
        assertThat(review.getIsPublished()).isFalse();
        assertThat(review.getContent()).isEqualTo("D".repeat(70));
        verify(moderationQueueService).submit(any(ModerationSubmitRequest.class), eq(userId));
    }

    @Test
    @DisplayName("사용자별 리뷰 목록 조회 - 성공")
    void getUserReviews_Success() {
        Long userId = 1L;
        Long releaseId = 100L;
        Long artistId = 200L;

        given(userRepository.existsById(userId)).willReturn(true);

        Review review = review(null, userId, releaseId, false, "Great Album!");
        Page<Review> reviewPage = new PageImpl<>(List.of(review));
        given(reviewRepository.findByUserIdAndStatus(eq(userId), eq(ReviewStatus.ACTIVE), any(Pageable.class)))
                .willReturn(reviewPage);

        Release release = activeRelease(releaseId, artistId, "Test Title");
        given(releaseRepository.findAllById(Set.of(releaseId))).willReturn(List.of(release));

        Artist artist = Artist.builder().name("Test Artist").build();
        setField(artist, "id", artistId);
        given(artistRepository.findAllById(Set.of(artistId))).willReturn(List.of(artist));

        PagedResponse<UserReviewResponse> response = reviewService.getUserReviews(userId, 1, 20);

        assertThat(response.data()).hasSize(1);
        UserReviewResponse reviewResponse = response.data().get(0);
        assertThat(reviewResponse.releaseTitle()).isEqualTo("Test Title");
        assertThat(reviewResponse.artistName()).isEqualTo("Test Artist");
        assertThat(reviewResponse.content()).isEqualTo("Great Album!");
        assertThat(response.pagination().totalItems()).isEqualTo(1L);
    }

    @Test
    @DisplayName("사용자별 리뷰 목록 조회 - 실패(존재하지 않는 사용자)")
    void getUserReviews_Fail_UserNotFound() {
        Long userId = 999L;
        given(userRepository.existsById(userId)).willReturn(false);

        NotFoundException exception = assertThrows(NotFoundException.class, () ->
                reviewService.getUserReviews(userId, 1, 20)
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TARGET_USER_NOT_FOUND);
        verify(moderationQueueService, never()).submit(any(ModerationSubmitRequest.class), any(Long.class));
    }

    private Review review(final Long reviewId, final Long userId, final Long releaseId,
                          final boolean isPublished, final String content) {
        Review review = Review.builder()
                .userId(userId)
                .releaseId(releaseId)
                .content(content)
                .isPublished(isPublished)
                .build();
        if (reviewId != null) {
            setField(review, "id", reviewId);
        }
        setField(review, "status", ReviewStatus.ACTIVE);
        return review;
    }

    private Release activeRelease(final Long releaseId, final Long artistId, final String title) {
        Release release = Release.builder()
                .artistId(artistId)
                .title(title)
                .releaseType(ReleaseType.ALBUM)
                .releaseDate(LocalDate.now())
                .build();
        setField(release, "id", releaseId);
        setField(release, "status", ReleaseStatus.ACTIVE);
        return release;
    }

    private User user(final Long userId, final String username) {
        User user = User.builder()
                .username(username)
                .email(username + "@example.com")
                .passwordHash("hashed-password")
                .build();
        setField(user, "id", userId);
        return user;
    }

    private void setField(final Object target, final String fieldName, final Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
