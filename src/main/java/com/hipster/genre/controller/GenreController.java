package com.hipster.genre.controller;

import com.hipster.auth.annotation.CurrentUser;
import com.hipster.auth.dto.CurrentUserInfo;
import com.hipster.genre.dto.CreateGenreRequest;
import com.hipster.genre.dto.GenreNodeResponse;
import com.hipster.genre.service.GenreService;
import com.hipster.global.dto.ApiResponse;
import com.hipster.moderation.dto.ModerationSubmitResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/genres")
@RequiredArgsConstructor
public class GenreController {

    private final GenreService genreService;

    @PostMapping
    public ResponseEntity<ApiResponse<ModerationSubmitResponse>> createGenre(
            @RequestBody @Valid final CreateGenreRequest request,
            @CurrentUser final CurrentUserInfo user) {
        final ModerationSubmitResponse response = genreService.createGenre(request, user.userId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.of(HttpStatus.ACCEPTED.value(), "장르 등록 요청이 접수되었습니다.", response));
    }

    @GetMapping("/tree")
    public ResponseEntity<ApiResponse<List<GenreNodeResponse>>> getGenreTree() {
        return ResponseEntity.ok(ApiResponse.ok(genreService.getGenreTree()));
    }
}
