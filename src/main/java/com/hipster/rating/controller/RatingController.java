package com.hipster.rating.controller;

import com.hipster.auth.annotation.CurrentUser;
import com.hipster.auth.dto.response.CurrentUserInfo;
import com.hipster.global.dto.response.ApiResponse;
import com.hipster.global.dto.response.PagedResponse;
import com.hipster.rating.dto.request.CreateRatingRequest;
import com.hipster.rating.dto.response.RatingResponse;
import com.hipster.rating.dto.response.RatingResult;
import com.hipster.rating.dto.response.UserRatingResponse;
import com.hipster.rating.service.RatingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RatingController {

    private final RatingService ratingService;

    @PostMapping("/releases/{releaseId}/ratings")
    public ResponseEntity<ApiResponse<RatingResponse>> createOrUpdateRating(
            @PathVariable final Long releaseId,
            @RequestBody @Valid final CreateRatingRequest request,
            @CurrentUser final CurrentUserInfo userInfo) {

        final RatingResult result = ratingService.createOrUpdateRating(releaseId, request, userInfo.userId());

        if (result.isCreated()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.of(HttpStatus.CREATED.value(), "평점이 등록되었습니다.", result.response()));
        }
        return ResponseEntity.ok(ApiResponse.ok(result.response()));
    }

    @GetMapping("/users/{userId}/ratings")
    public ResponseEntity<ApiResponse<PagedResponse<UserRatingResponse>>> getUserRatings(
            @PathVariable final Long userId,
            @RequestParam(defaultValue = "1") final int page,
            @RequestParam(defaultValue = "20") final int limit) {
        return ResponseEntity.ok(ApiResponse.ok(ratingService.getUserRatings(userId, page, limit)));
    }
}
