package com.hipster.moderation.dto.response;

import com.hipster.global.dto.response.PaginationDto;
import java.util.List;

public record ModerationQueueListResponse(
        Long totalPending,
        Long totalUnderReview,
        Long totalSlaBreached,
        Long slaTargetHours,
        List<ModerationQueueItemResponse> items,
        PaginationDto pagination
) {
}
