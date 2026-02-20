package com.hipster.moderation.controller;

import com.hipster.auth.UserRole;
import com.hipster.auth.annotation.CurrentUser;
import com.hipster.auth.annotation.RequireRole;
import com.hipster.auth.dto.CurrentUserInfo;
import com.hipster.global.dto.ApiResponse;
import com.hipster.moderation.domain.ModerationStatus;
import com.hipster.moderation.dto.ApproveRequest;
import com.hipster.moderation.dto.ModerationQueueItemResponse;
import com.hipster.moderation.dto.ModerationQueueListResponse;
import com.hipster.moderation.dto.RejectRequest;
import com.hipster.moderation.service.ModerationQueueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/moderation")
@RequiredArgsConstructor
public class ModerationController {

    private final ModerationQueueService moderationQueueService;

    @GetMapping("/queue")
    @RequireRole({UserRole.MODERATOR, UserRole.ADMIN})
    public ResponseEntity<ApiResponse<ModerationQueueListResponse>> getModerationQueue(
            @RequestParam(required = false) final ModerationStatus status,
            @RequestParam(required = false) final Integer priority,
            @RequestParam(defaultValue = "1") final int page,
            @RequestParam(defaultValue = "50") final int limit) {
        return ResponseEntity.ok(ApiResponse.ok(moderationQueueService.getModerationQueue(status, priority, page, limit)));
    }

    @PostMapping("/queue/{id}/claim")
    @RequireRole({UserRole.MODERATOR, UserRole.ADMIN})
    public ResponseEntity<ApiResponse<ModerationQueueItemResponse>> claimQueueItem(
            @PathVariable final Long id,
            @CurrentUser final CurrentUserInfo moderator) {
        return ResponseEntity.ok(ApiResponse.ok(moderationQueueService.claimQueueItem(id, moderator.userId())));
    }

    @PostMapping("/queue/{id}/approve")
    @RequireRole({UserRole.MODERATOR, UserRole.ADMIN})
    public ResponseEntity<ApiResponse<Void>> approveQueueItem(
            @PathVariable final Long id,
            @CurrentUser final CurrentUserInfo moderator,
            @RequestBody(required = false) final ApproveRequest request) {
        final String comment = request != null ? request.comment() : null;
        moderationQueueService.approve(id, moderator.userId(), comment);
        return ResponseEntity.ok(ApiResponse.of(200, "승인 처리되었습니다.", null));
    }

    @PostMapping("/queue/{id}/reject")
    @RequireRole({UserRole.MODERATOR, UserRole.ADMIN})
    public ResponseEntity<ApiResponse<Void>> rejectQueueItem(
            @PathVariable final Long id,
            @CurrentUser final CurrentUserInfo moderator,
            @RequestBody @Valid final RejectRequest request) {
        moderationQueueService.reject(id, moderator.userId(), request.reason(), request.comment());
        return ResponseEntity.ok(ApiResponse.of(200, "거절 처리되었습니다.", null));
    }
}
