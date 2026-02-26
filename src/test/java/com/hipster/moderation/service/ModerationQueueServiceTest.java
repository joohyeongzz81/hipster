package com.hipster.moderation.service;

import com.hipster.global.dto.response.PagedResponse;
import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.domain.ModerationQueue;
import com.hipster.moderation.domain.ModerationStatus;
import com.hipster.moderation.domain.RejectionReason;
import com.hipster.moderation.dto.response.UserModerationSubmissionResponse;
import com.hipster.moderation.repository.ModerationQueueRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ModerationQueueServiceTest {

    @InjectMocks
    private ModerationQueueService moderationQueueService;

    @Mock
    private ModerationQueueRepository moderationQueueRepository;

    @Test
    @DisplayName("사용자 모더레이션 요청 목록 조회 - 정상 동작")
    void getUserSubmissions_Success() {
        // given
        Long submitterId = 1L;
        int page = 1;
        int limit = 20;

        ModerationQueue queueItem = ModerationQueue.builder()
                .entityType(EntityType.RELEASE)
                .entityId(10L)
                .submitterId(submitterId)
                .metaComment("Test release submission")
                .priority(2)
                .build();
        
        queueItem.reject(RejectionReason.INCORRECT_INFORMATION, "Rejected due to policy");

        Page<ModerationQueue> mockPage = new PageImpl<>(List.of(queueItem));

        given(moderationQueueRepository.findBySubmitterIdOrderBySubmittedAtDesc(eq(submitterId), any(Pageable.class)))
                .willReturn(mockPage);

        // when
        PagedResponse<UserModerationSubmissionResponse> response = moderationQueueService.getUserSubmissions(submitterId, page, limit);

        // then
        assertThat(response.data()).hasSize(1);
        UserModerationSubmissionResponse itemResponse = response.data().get(0);
        assertThat(itemResponse.status()).isEqualTo(ModerationStatus.REJECTED);
        assertThat(itemResponse.rejectionReason()).isEqualTo(RejectionReason.INCORRECT_INFORMATION.name());
        assertThat(itemResponse.moderatorComment()).isEqualTo("Rejected due to policy");
        assertThat(response.pagination().totalItems()).isEqualTo(1L);
    }
}
