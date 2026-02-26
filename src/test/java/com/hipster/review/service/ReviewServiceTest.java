package com.hipster.review.service;

import com.hipster.artist.domain.Artist;
import com.hipster.artist.repository.ArtistRepository;
import com.hipster.global.dto.response.PagedResponse;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.NotFoundException;
import com.hipster.release.domain.Release;
import com.hipster.release.domain.ReleaseType;
import com.hipster.release.repository.ReleaseRepository;
import com.hipster.review.domain.Review;
import com.hipster.review.domain.ReviewStatus;
import com.hipster.review.dto.response.UserReviewResponse;
import com.hipster.review.repository.ReviewRepository;
import com.hipster.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

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

    @Test
    @DisplayName("사용자별 리뷰 목록 조회 - 성공")
    void getUserReviews_Success() {
        // given
        Long userId = 1L;
        Long releaseId = 100L;
        Long artistId = 200L;

        given(userRepository.existsById(userId)).willReturn(true);

        Review review = Review.builder().userId(userId).releaseId(releaseId).content("Great Album!").build();
        Page<Review> reviewPage = new PageImpl<>(List.of(review));
        given(reviewRepository.findByUserIdAndStatus(eq(userId), eq(ReviewStatus.ACTIVE), any(Pageable.class)))
                .willReturn(reviewPage);

        Release release = Release.builder().artistId(artistId).title("Test Title").releaseType(ReleaseType.ALBUM).releaseDate(LocalDate.now()).build();
        given(releaseRepository.findAllById(Set.of(releaseId))).willReturn(List.of(release));

        Artist artist = Artist.builder().name("Test Artist").build();
        given(artistRepository.findAllById(Set.of(artistId))).willReturn(List.of(artist));

        // when
        PagedResponse<UserReviewResponse> response = reviewService.getUserReviews(userId, 1, 20);

        // then
        assertThat(response.content()).hasSize(1);
        UserReviewResponse reviewResponse = response.content().get(0);
        assertThat(reviewResponse.releaseTitle()).isEqualTo("Test Title");
        assertThat(reviewResponse.artistName()).isEqualTo("Test Artist");
        assertThat(reviewResponse.content()).isEqualTo("Great Album!");
        assertThat(response.pagination().totalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("사용자별 리뷰 목록 조회 - 실패 (존재하지 않는 사용자)")
    void getUserReviews_Fail_UserNotFound() {
        // given
        Long userId = 999L;
        given(userRepository.existsById(userId)).willReturn(false);

        // when & then
        NotFoundException exception = assertThrows(NotFoundException.class, () ->
                reviewService.getUserReviews(userId, 1, 20)
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TARGET_USER_NOT_FOUND);
    }
}
