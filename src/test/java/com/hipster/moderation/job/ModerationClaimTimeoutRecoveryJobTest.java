package com.hipster.moderation.job;

import com.hipster.moderation.service.ModerationQueueService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ModerationClaimTimeoutRecoveryJobTest {

    @InjectMocks
    private ModerationClaimTimeoutRecoveryJob moderationClaimTimeoutRecoveryJob;

    @Mock
    private ModerationQueueService moderationQueueService;

    @Test
    @DisplayName("만료 claim 회수 작업은 moderation service 회수를 호출한다")
    void recoverExpiredClaims_DelegatesToService() {
        given(moderationQueueService.releaseExpiredClaims()).willReturn(2);

        moderationClaimTimeoutRecoveryJob.recoverExpiredClaims();

        verify(moderationQueueService).releaseExpiredClaims();
    }
}
