package com.hipster.moderation.controller;

import com.hipster.auth.UserRole;
import com.hipster.auth.annotation.CurrentUser;
import com.hipster.auth.annotation.RequireRole;
import com.hipster.auth.dto.CurrentUserInfo;
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
    public ResponseEntity<ModerationQueueListResponse> getModerationQueue(
            @RequestParam(required = false) ModerationStatus status,
            @RequestParam(required = false) Integer priority,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(moderationQueueService.getModerationQueue(status, priority, page, limit));
    }

    @PostMapping("/queue/{id}/claim")
    @RequireRole({UserRole.MODERATOR, UserRole.ADMIN})
    public ResponseEntity<ModerationQueueItemResponse> claimQueueItem(
            @PathVariable Long id,
            @CurrentUser CurrentUserInfo moderator) {
        return ResponseEntity.ok(moderationQueueService.claimQueueItem(id, moderator.userId()));
    }

    @PostMapping("/queue/{id}/approve")
    @RequireRole({UserRole.MODERATOR, UserRole.ADMIN})
    public ResponseEntity<Void> approveQueueItem(
            @PathVariable Long id,
            @CurrentUser CurrentUserInfo moderator,
            @RequestBody(required = false) ApproveRequest request) {
        String comment = request != null ? request.comment() : null;
        moderationQueueService.approve(id, moderator.userId(), comment);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/queue/{id}/reject")
    @RequireRole({UserRole.MODERATOR, UserRole.ADMIN})
    public ResponseEntity<Void> rejectQueueItem(
            @PathVariable Long id,
            @CurrentUser CurrentUserInfo moderator,
            @RequestBody @Valid RejectRequest request) {
        moderationQueueService.reject(id, moderator.userId(), request.reason(), request.comment());
        return ResponseEntity.ok().build();
    }
}
