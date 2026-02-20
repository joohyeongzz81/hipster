package com.hipster.artist.controller;

import com.hipster.artist.dto.response.ArtistResponse;
import com.hipster.artist.dto.request.CreateArtistRequest;
import com.hipster.artist.service.ArtistService;
import com.hipster.auth.annotation.CurrentUser;
import com.hipster.auth.dto.response.CurrentUserInfo;
import com.hipster.global.dto.response.ApiResponse;
import com.hipster.global.dto.response.PagedResponse;
import com.hipster.moderation.dto.response.ModerationSubmitResponse;
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
    public ResponseEntity<ApiResponse<ModerationSubmitResponse>> createArtist(
            @RequestBody @Valid final CreateArtistRequest request,
            @CurrentUser final CurrentUserInfo user) {
        final ModerationSubmitResponse response = artistService.createArtist(request, user.userId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.of(HttpStatus.ACCEPTED.value(), "아티스트 등록 요청이 접수되었습니다.", response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ArtistResponse>> getArtist(@PathVariable final Long id) {
        return ResponseEntity.ok(ApiResponse.ok(artistService.getArtist(id)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ArtistResponse>>> searchArtists(
            @RequestParam(defaultValue = "") final String q,
            @RequestParam(defaultValue = "1") final int page,
            @RequestParam(defaultValue = "20") final int limit) {
        return ResponseEntity.ok(ApiResponse.ok(artistService.searchArtists(q, page, limit)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteArtist(@PathVariable final Long id) {
        artistService.deleteArtist(id);
        return ResponseEntity.ok(ApiResponse.of(200, "아티스트가 삭제되었습니다.", null));
    }
}
