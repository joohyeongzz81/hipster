package com.hipster.review.controller;

import com.hipster.auth.annotation.CurrentUser;
import com.hipster.auth.dto.response.CurrentUserInfo;
import com.hipster.global.dto.response.ApiResponse;
import com.hipster.global.dto.response.PagedResponse;
import com.hipster.review.dto.request.CreateReviewRequest;
import com.hipster.review.dto.request.UpdateReviewRequest;
import com.hipster.review.dto.response.ReviewResponse;
import com.hipster.review.dto.response.UserReviewResponse;
import com.hipster.review.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping("/releases/{releaseId}/reviews")
    public ResponseEntity<ApiResponse<ReviewResponse>> createReview(
            @PathVariable final Long releaseId,
            @RequestBody @Valid final CreateReviewRequest request,
            @CurrentUser final CurrentUserInfo user) {
        final ReviewResponse response = reviewService.createReview(releaseId, request, user.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(HttpStatus.CREATED.value(), "리뷰가 등록되었습니다.", response));
    }

    @GetMapping("/releases/{releaseId}/reviews")
    public ResponseEntity<ApiResponse<PagedResponse<ReviewResponse>>> getReviewsByRelease(
            @PathVariable final Long releaseId,
            @RequestParam(defaultValue = "1") final int page,
            @RequestParam(defaultValue = "20") final int limit) {
        return ResponseEntity.ok(ApiResponse.ok(reviewService.getReviewsByRelease(releaseId, page, limit)));
    }

    @GetMapping("/reviews/{id}")
    public ResponseEntity<ApiResponse<ReviewResponse>> getReview(@PathVariable final Long id) {
        return ResponseEntity.ok(ApiResponse.ok(reviewService.getReview(id)));
    }

    @PutMapping("/reviews/{id}")
    public ResponseEntity<ApiResponse<ReviewResponse>> updateReview(
            @PathVariable final Long id,
            @RequestBody @Valid final UpdateReviewRequest request,
            @CurrentUser final CurrentUserInfo user) {
        return ResponseEntity.ok(ApiResponse.ok(reviewService.updateReview(id, request, user.userId())));
    }

    @DeleteMapping("/reviews/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteReview(
            @PathVariable final Long id,
            @CurrentUser final CurrentUserInfo user) {
        reviewService.deleteReview(id, user.userId());
        return ResponseEntity.ok(ApiResponse.of(200, "리뷰가 삭제되었습니다.", null));
    }

    @GetMapping("/users/{userId}/reviews")
    public ResponseEntity<ApiResponse<PagedResponse<UserReviewResponse>>> getUserReviews(
            @PathVariable final Long userId,
            @RequestParam(defaultValue = "1") final int page,
            @RequestParam(defaultValue = "20") final int limit) {
        return ResponseEntity.ok(ApiResponse.ok(reviewService.getUserReviews(userId, page, limit)));
    }
}
