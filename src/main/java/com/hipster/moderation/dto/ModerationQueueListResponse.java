package com.hipster.moderation.dto;

import com.hipster.global.dto.PaginationDto;
import java.util.List;

public record ModerationQueueListResponse(
        Long totalPending,
        List<ModerationQueueItemResponse> items,
        PaginationDto pagination
) {
}
