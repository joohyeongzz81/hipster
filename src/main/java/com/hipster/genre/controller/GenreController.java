package com.hipster.genre.controller;

import com.hipster.auth.annotation.CurrentUser;
import com.hipster.auth.dto.CurrentUserInfo;
import com.hipster.genre.dto.CreateGenreRequest;
import com.hipster.genre.dto.GenreNodeResponse;
import com.hipster.genre.service.GenreService;
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
    public ResponseEntity<ModerationSubmitResponse> createGenre(
            @RequestBody @Valid CreateGenreRequest request,
            @CurrentUser CurrentUserInfo user
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(genreService.createGenre(request, user.userId()));
    }

    @GetMapping("/tree")
    public ResponseEntity<List<GenreNodeResponse>> getGenreTree() {
        return ResponseEntity.ok(genreService.getGenreTree());
    }
}
