package com.hipster.moderation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hipster.artist.repository.ArtistRepository;
import com.hipster.genre.repository.GenreRepository;
import com.hipster.global.dto.response.PagedResponse;
import com.hipster.global.exception.BadRequestException;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.ForbiddenException;
import com.hipster.global.exception.NotFoundException;
import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.domain.ModerationQueue;
import com.hipster.moderation.domain.ModerationStatus;
import com.hipster.moderation.domain.RejectionReason;
import com.hipster.moderation.dto.response.ModerationQueueItemResponse;
import com.hipster.moderation.dto.response.UserModerationSubmissionResponse;
import com.hipster.moderation.repository.ModerationQueueRepository;
import com.hipster.release.domain.Release;
import com.hipster.release.domain.ReleaseType;
import com.hipster.release.repository.ReleaseRepository;
import com.hipster.review.domain.Review;
import com.hipster.review.repository.ReviewRepository;
import com.hipster.user.domain.User;
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
import org.springframework.data.jpa.domain.Specification;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ModerationQueueServiceTest {

    @InjectMocks
    private ModerationQueueService moderationQueueService;

    @Mock
    private ModerationQueueRepository moderationQueueRepository;

    @Mock
    private SpamDetectionService spamDetectionService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ReleaseRepository releaseRepository;

    @Mock
    private ArtistRepository artistRepository;

    @Mock
    private GenreRepository genreRepository;

    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("사용자 모더레이션 요청 목록 조회 - 정상 동작")
    void getUserSubmissions_Success() {
        Long submitterId = 1L;
        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 10L, submitterId, "Test release submission");
        queueItem.reject(RejectionReason.INCORRECT_INFORMATION, "Rejected due to policy");

        Page<ModerationQueue> mockPage = new PageImpl<>(List.of(queueItem));
        given(moderationQueueRepository.findBySubmitterIdOrderBySubmittedAtDesc(eq(submitterId), any(Pageable.class)))
                .willReturn(mockPage);

        PagedResponse<UserModerationSubmissionResponse> response = moderationQueueService.getUserSubmissions(submitterId, 1, 20);

        assertThat(response.data()).hasSize(1);
        UserModerationSubmissionResponse itemResponse = response.data().get(0);
        assertThat(itemResponse.status()).isEqualTo(ModerationStatus.REJECTED);
        assertThat(itemResponse.rejectionReason()).isEqualTo(RejectionReason.INCORRECT_INFORMATION.name());
        assertThat(itemResponse.moderatorComment()).isEqualTo("Rejected due to policy");
        assertThat(response.pagination().totalItems()).isEqualTo(1L);
    }

    @Test
    @DisplayName("이미 처리 완료된 항목은 다시 승인할 수 없다")
    void approve_AlreadyProcessed_ThrowsException() {
        Long queueId = 1L;
        Long submitterId = 10L;
        Long moderatorId = 20L;

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 100L, submitterId, "Approved already");
        queueItem.assignModerator(moderatorId, LocalDateTime.now(), LocalDateTime.now().plusMinutes(30));
        queueItem.approve("Approved once");

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));

        assertThatThrownBy(() -> moderationQueueService.approve(queueId, moderatorId, "Approve again"))
                .isInstanceOfSatisfying(BadRequestException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MODERATION_ALREADY_PROCESSED));

        verify(moderationQueueRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 처리 완료된 항목은 다시 반려할 수 없다")
    void reject_AlreadyProcessed_ThrowsException() {
        Long queueId = 2L;
        Long submitterId = 11L;
        Long moderatorId = 21L;

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 101L, submitterId, "Auto approved already");
        queueItem.autoApprove();

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));

        assertThatThrownBy(() -> moderationQueueService.reject(
                queueId, moderatorId, RejectionReason.INCORRECT_INFORMATION, "Reject again"))
                .isInstanceOfSatisfying(BadRequestException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MODERATION_ALREADY_PROCESSED));

        verify(moderationQueueRepository, never()).save(any());
    }

    @Test
    @DisplayName("만료된 claim 은 다른 모더레이터가 다시 점유할 수 있다")
    void claimQueueItem_ExpiredClaim_ReassignsModerator() {
        Long queueId = 3L;
        Long submitterId = 12L;
        Long previousModeratorId = 30L;
        Long newModeratorId = 31L;
        LocalDateTime claimedAt = LocalDateTime.now().minusHours(2);

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 102L, submitterId, "Expired claim");
        queueItem.assignModerator(previousModeratorId, claimedAt, claimedAt.plusMinutes(30));

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));
        given(userRepository.findById(submitterId)).willReturn(Optional.empty());

        ModerationQueueItemResponse response = moderationQueueService.claimQueueItem(queueId, newModeratorId);

        assertThat(response.status()).isEqualTo(ModerationStatus.UNDER_REVIEW);
        assertThat(queueItem.getModeratorId()).isEqualTo(newModeratorId);
        assertThat(queueItem.getClaimedAt()).isNotNull();
        assertThat(queueItem.getClaimExpiresAt()).isNotNull();
        assertThat(Duration.between(queueItem.getClaimedAt(), queueItem.getClaimExpiresAt())).isEqualTo(Duration.ofMinutes(30));
        verify(moderationQueueRepository).save(queueItem);
    }

    @Test
    @DisplayName("claim 이 만료되면 기존 모더레이터는 승인할 수 없다")
    void approve_ExpiredClaim_ThrowsNotClaimed() {
        Long queueId = 4L;
        Long submitterId = 13L;
        Long moderatorId = 32L;
        LocalDateTime claimedAt = LocalDateTime.now().minusHours(1);

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 103L, submitterId, "Expired before approve");
        queueItem.assignModerator(moderatorId, claimedAt, claimedAt.plusMinutes(30));

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));

        assertThatThrownBy(() -> moderationQueueService.approve(queueId, moderatorId, "Approve after expiry"))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MODERATION_NOT_CLAIMED));

        assertThat(queueItem.getStatus()).isEqualTo(ModerationStatus.PENDING);
        assertThat(queueItem.getModeratorId()).isNull();
        verify(moderationQueueRepository).save(queueItem);
    }

    @Test
    @DisplayName("모더레이터는 자신이 점유한 항목을 반납할 수 있다")
    void unclaimQueueItem_Success() {
        Long queueId = 5L;
        Long submitterId = 14L;
        Long moderatorId = 33L;
        LocalDateTime claimedAt = LocalDateTime.now().minusMinutes(5);

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 104L, submitterId, "Need unclaim");
        queueItem.assignModerator(moderatorId, claimedAt, claimedAt.plusMinutes(30));

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));

        moderationQueueService.unclaimQueueItem(queueId, moderatorId);

        assertThat(queueItem.getStatus()).isEqualTo(ModerationStatus.PENDING);
        assertThat(queueItem.getModeratorId()).isNull();
        assertThat(queueItem.getClaimedAt()).isNull();
        assertThat(queueItem.getClaimExpiresAt()).isNull();
        verify(moderationQueueRepository).save(queueItem);
    }

    @Test
    @DisplayName("대기열 조회 전에 만료된 claim 을 자동 회수한다")
    void getModerationQueue_ReleasesExpiredClaimsBeforeListing() {
        Long submitterId = 15L;
        Long moderatorId = 34L;
        LocalDateTime claimedAt = LocalDateTime.now().minusHours(3);

        ModerationQueue expiredItem = queueItem(EntityType.RELEASE, 105L, submitterId, "Release before listing");
        expiredItem.assignModerator(moderatorId, claimedAt, claimedAt.plusMinutes(30));

        given(moderationQueueRepository.findExpiredClaims(eq(ModerationStatus.UNDER_REVIEW), any(LocalDateTime.class)))
                .willReturn(List.of(expiredItem));
        given(moderationQueueRepository.findAll(org.mockito.ArgumentMatchers.<Specification<ModerationQueue>>any(), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));
        given(moderationQueueRepository.count(org.mockito.ArgumentMatchers.<Specification<ModerationQueue>>any())).willReturn(0L);
        given(userRepository.findAllById(any())).willReturn(List.of());

        moderationQueueService.getModerationQueue(null, null, 1, 20);

        assertThat(expiredItem.getStatus()).isEqualTo(ModerationStatus.PENDING);
        assertThat(expiredItem.getModeratorId()).isNull();
        verify(moderationQueueRepository).saveAll(List.of(expiredItem));
    }

    @Test
    @DisplayName("리뷰 승인 시 실제 리뷰 공개 상태와 moderation 상태를 함께 반영한다")
    void approve_Review_PublishesReviewAndMarksApproved() {
        Long queueId = 6L;
        Long submitterId = 16L;
        Long moderatorId = 35L;

        ModerationQueue queueItem = queueItem(EntityType.REVIEW, 200L, submitterId, "Publish review");
        queueItem.assignModerator(moderatorId, LocalDateTime.now(), LocalDateTime.now().plusMinutes(30));

        Review review = Review.builder()
                .userId(submitterId)
                .releaseId(300L)
                .content("A".repeat(60))
                .isPublished(false)
                .build();

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));
        given(reviewRepository.findById(200L)).willReturn(Optional.of(review));

        moderationQueueService.approve(queueId, moderatorId, "Looks good");

        assertThat(review.getIsPublished()).isTrue();
        assertThat(queueItem.getStatus()).isEqualTo(ModerationStatus.APPROVED);
        verify(moderationQueueRepository).save(queueItem);
    }

    @Test
    @DisplayName("릴리즈 반려 시 실제 릴리즈 상태와 moderation 상태를 함께 반영한다")
    void reject_Release_DeletesReleaseAndMarksRejected() {
        Long queueId = 7L;
        Long submitterId = 17L;
        Long moderatorId = 36L;

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 201L, submitterId, "Reject release");
        queueItem.assignModerator(moderatorId, LocalDateTime.now(), LocalDateTime.now().plusMinutes(30));

        Release release = Release.builder()
                .artistId(10L)
                .title("Pending release")
                .releaseType(ReleaseType.ALBUM)
                .releaseDate(LocalDate.now())
                .build();

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));
        given(releaseRepository.findById(201L)).willReturn(Optional.of(release));

        moderationQueueService.reject(queueId, moderatorId, RejectionReason.INCORRECT_INFORMATION, "Rejected");

        assertThat(release.getStatus().name()).isEqualTo("DELETED");
        assertThat(queueItem.getStatus()).isEqualTo(ModerationStatus.REJECTED);
        verify(moderationQueueRepository).save(queueItem);
    }

    @Test
    @DisplayName("승인 대상 엔티티가 없으면 moderation 상태를 완료로 바꾸지 않는다")
    void approve_MissingRelease_ThrowsNotFound() {
        Long queueId = 8L;
        Long submitterId = 18L;
        Long moderatorId = 37L;

        ModerationQueue queueItem = queueItem(EntityType.RELEASE, 202L, submitterId, "Missing release");
        queueItem.assignModerator(moderatorId, LocalDateTime.now(), LocalDateTime.now().plusMinutes(30));

        given(moderationQueueRepository.findById(queueId)).willReturn(Optional.of(queueItem));
        given(releaseRepository.findById(202L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> moderationQueueService.approve(queueId, moderatorId, "Approve"))
                .isInstanceOfSatisfying(NotFoundException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RELEASE_NOT_FOUND));

        assertThat(queueItem.getStatus()).isEqualTo(ModerationStatus.UNDER_REVIEW);
        verify(moderationQueueRepository, never()).save(queueItem);
    }

    @Test
    @DisplayName("제출 스냅샷은 moderation queue 에 직렬화되어 저장된다")
    void submit_SerializesSnapshotAndSavesQueueItem() throws Exception {
        Long submitterId = 19L;
        User user = User.builder()
                .username("submitter")
                .email("submitter@example.com")
                .passwordHash("hashed-password")
                .build();

        given(objectMapper.writeValueAsString(any())).willReturn("{\"name\":\"Test Artist\"}");
        given(spamDetectionService.detectSpamPattern(eq(submitterId), eq(EntityType.ARTIST), eq(300L), any()))
                .willReturn(false);
        given(userRepository.findById(submitterId)).willReturn(Optional.of(user));
        given(moderationQueueRepository.save(any(ModerationQueue.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        moderationQueueService.submit(
                new com.hipster.moderation.dto.request.ModerationSubmitRequest(
                        EntityType.ARTIST,
                        300L,
                        "Artist submission for moderation review.",
                        Map.of("name", "Test Artist")
                ),
                submitterId
        );

        verify(moderationQueueRepository).save(argThat(item ->
                "{\"name\":\"Test Artist\"}".equals(item.getSubmissionSnapshot())
                        && item.getEntityType() == EntityType.ARTIST
                        && item.getEntityId().equals(300L)
        ));
    }

    private ModerationQueue queueItem(final EntityType entityType, final Long entityId, final Long submitterId,
                                      final String metaComment) {
        return ModerationQueue.builder()
                .entityType(entityType)
                .entityId(entityId)
                .submitterId(submitterId)
                .metaComment(metaComment)
                .priority(2)
                .build();
    }
}
