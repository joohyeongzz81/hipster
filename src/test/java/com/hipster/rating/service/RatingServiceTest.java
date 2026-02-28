package com.hipster.rating.service;

import com.hipster.artist.domain.Artist;
import com.hipster.artist.repository.ArtistRepository;
import com.hipster.global.dto.response.PagedResponse;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.NotFoundException;
import com.hipster.rating.domain.Rating;
import com.hipster.rating.dto.response.UserRatingResponse;
import com.hipster.rating.repository.RatingRepository;
import com.hipster.release.domain.Release;
import com.hipster.release.domain.ReleaseType;
import com.hipster.release.repository.ReleaseRepository;
import com.hipster.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RatingServiceTest {

    @InjectMocks
    private RatingService ratingService;

    @Mock
    private RatingRepository ratingRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ReleaseRepository releaseRepository;

    @Mock
    private ArtistRepository artistRepository;

    @Test
    @DisplayName("사용자별 평점 목록 조회 - 성공")
    void getUserRatings_Success() {
        // given
        Long userId = 1L;
        Long releaseId = 100L;
        Long artistId = 200L;

        given(userRepository.existsById(userId)).willReturn(true);

        Rating rating = Rating.builder().userId(userId).releaseId(releaseId).score(4.5).build();
        Page<Rating> ratingPage = new PageImpl<>(List.of(rating));
        given(ratingRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any(Pageable.class)))
                .willReturn(ratingPage);

        Release release = Release.builder().artistId(artistId).title("Test Title").releaseType(ReleaseType.ALBUM).releaseDate(LocalDate.now()).build();
        given(releaseRepository.findAllById(Set.of(releaseId))).willReturn(List.of(release));

        Artist artist = Artist.builder().name("Test Artist").build();
        given(artistRepository.findAllById(Set.of(artistId))).willReturn(List.of(artist));

        // when
        PagedResponse<UserRatingResponse> response = ratingService.getUserRatings(userId, 1, 20);

        // then
        assertThat(response.data()).hasSize(1);
        UserRatingResponse ratingResponse = response.data().get(0);
        assertThat(ratingResponse.releaseTitle()).isEqualTo("Test Title");
        assertThat(ratingResponse.artistName()).isEqualTo("Test Artist");
        assertThat(ratingResponse.score()).isEqualTo(4.5);
        assertThat(response.pagination().totalItems()).isEqualTo(1L);
    }

    @Test
    @DisplayName("사용자별 평점 목록 조회 - 실패 (존재하지 않는 사용자)")
    void getUserRatings_Fail_UserNotFound() {
        // given
        Long userId = 999L;
        given(userRepository.existsById(userId)).willReturn(false);

        // when & then
        NotFoundException exception = assertThrows(NotFoundException.class, () ->
                ratingService.getUserRatings(userId, 1, 20)
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TARGET_USER_NOT_FOUND);
    }
}
