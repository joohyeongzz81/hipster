package com.hipster.release.controller;

import com.hipster.global.dto.PagedResponse;
import com.hipster.release.dto.ReleaseSearchRequest;
import com.hipster.release.dto.ReleaseSummaryResponse;
import com.hipster.release.service.ReleaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
}
