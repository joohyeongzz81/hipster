package com.hipster.track.controller;

import com.hipster.global.dto.ApiResponse;
import com.hipster.track.dto.TrackResponse;
import com.hipster.track.service.TrackService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/releases/{releaseId}/tracks")
@RequiredArgsConstructor
public class TrackController {

    private final TrackService trackService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TrackResponse>>> getTracksByRelease(
            @PathVariable final Long releaseId) {
        return ResponseEntity.ok(ApiResponse.ok(trackService.getTracksByReleaseId(releaseId)));
    }
}
