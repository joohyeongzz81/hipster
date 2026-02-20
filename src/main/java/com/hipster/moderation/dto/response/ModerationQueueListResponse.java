package com.hipster.moderation.dto.response;

import com.hipster.global.dto.response.PaginationDto;
import java.util.List;

public record ModerationQueueListResponse(
        Long totalPending,
        List<ModerationQueueItemResponse> items,
        PaginationDto pagination
) {
}
