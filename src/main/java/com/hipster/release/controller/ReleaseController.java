package com.hipster.release.controller;

import com.hipster.auth.annotation.CurrentUser;
import com.hipster.auth.dto.response.CurrentUserInfo;
import com.hipster.global.dto.response.ApiResponse;
import com.hipster.global.dto.response.PagedResponse;
import com.hipster.moderation.dto.response.ModerationSubmitResponse;
import com.hipster.release.dto.request.CreateReleaseRequest;
import com.hipster.release.dto.response.ReleaseDetailResponse;
import com.hipster.release.dto.request.ReleaseSearchRequest;
import com.hipster.release.dto.response.ReleaseSummaryResponse;
import com.hipster.release.service.ReleaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    public ResponseEntity<ApiResponse<PagedResponse<ReleaseSummaryResponse>>> searchReleases(
            final ReleaseSearchRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(releaseService.searchReleases(request)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ModerationSubmitResponse>> createRelease(
            @RequestBody @Valid final CreateReleaseRequest request,
            @CurrentUser final CurrentUserInfo user) {
        final ModerationSubmitResponse response = releaseService.createRelease(request, user.userId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.of(HttpStatus.ACCEPTED.value(), "앨범 등록 요청이 접수되었습니다.", response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ReleaseDetailResponse>> getRelease(@PathVariable final Long id) {
        return ResponseEntity.ok(ApiResponse.ok(releaseService.getReleaseDetail(id)));
    }
}
