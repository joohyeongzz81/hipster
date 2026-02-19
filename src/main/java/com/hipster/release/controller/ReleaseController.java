package com.hipster.release.controller;

import com.hipster.global.dto.PagedResponse;
import com.hipster.release.dto.ReleaseSearchRequest;
import com.hipster.release.dto.ReleaseSummaryResponse;
import com.hipster.release.dto.CreateReleaseRequest;
import com.hipster.release.service.ReleaseService;
import com.hipster.auth.annotation.CurrentUser;
import com.hipster.auth.dto.CurrentUserInfo;
import com.hipster.moderation.dto.ModerationSubmitResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/releases")
@RequiredArgsConstructor
public class ReleaseController {

    private final ReleaseService releaseService;

    @GetMapping
    public ResponseEntity<PagedResponse<ReleaseSummaryResponse>> searchReleases(ReleaseSearchRequest request) {
        return ResponseEntity.ok(releaseService.searchReleases(request));
    }

    @PostMapping
    public ResponseEntity<ModerationSubmitResponse> createRelease(
            @RequestBody @Valid CreateReleaseRequest request,
            @CurrentUser CurrentUserInfo user
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(releaseService.createRelease(request, user.userId()));
    }
}
