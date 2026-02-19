package com.hipster.artist.controller;

import com.hipster.artist.dto.ArtistResponse;
import com.hipster.artist.dto.CreateArtistRequest;
import com.hipster.artist.service.ArtistService;
import com.hipster.auth.annotation.CurrentUser;
import com.hipster.auth.dto.CurrentUserInfo;
import com.hipster.global.dto.PagedResponse;
import com.hipster.moderation.dto.ModerationSubmitResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/artists")
@RequiredArgsConstructor
public class ArtistController {

    private final ArtistService artistService;

    @PostMapping
    public ResponseEntity<ModerationSubmitResponse> createArtist(
            @RequestBody @Valid CreateArtistRequest request,
            @CurrentUser CurrentUserInfo user
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(artistService.createArtist(request, user.userId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ArtistResponse> getArtist(@PathVariable Long id) {
        return ResponseEntity.ok(artistService.getArtist(id));
    }

    @GetMapping
    public ResponseEntity<PagedResponse<ArtistResponse>> searchArtists(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ResponseEntity.ok(artistService.searchArtists(q, page, limit));
    }
}
