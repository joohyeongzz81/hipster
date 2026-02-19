package com.hipster.artist.service;

import com.hipster.artist.domain.Artist;
import com.hipster.artist.dto.ArtistResponse;
import com.hipster.artist.dto.CreateArtistRequest;
import com.hipster.artist.repository.ArtistRepository;
import com.hipster.global.dto.PagedResponse;
import com.hipster.global.dto.PaginationDto;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.NotFoundException;
import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.dto.ModerationSubmitRequest;
import com.hipster.moderation.dto.ModerationSubmitResponse;
import com.hipster.moderation.service.ModerationQueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ArtistService {

    private final ArtistRepository artistRepository;
    private final ModerationQueueService moderationQueueService;

    @Transactional
    public ModerationSubmitResponse createArtist(CreateArtistRequest request, Long submitterId) {
        Artist artist = Artist.builder()
                .name(request.name())
                .description(request.description())
                .formedYear(request.formedYear())
                .country(request.country())
                .build();

        artist = artistRepository.save(artist);

        ModerationSubmitRequest modRequest = new ModerationSubmitRequest(
                EntityType.ARTIST,
                artist.getId(),
                request.metaComment()
        );

        return moderationQueueService.submit(modRequest, submitterId);
    }

    public ArtistResponse getArtist(Long id) {
        Artist artist = artistRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        return ArtistResponse.from(artist);
    }

    public PagedResponse<ArtistResponse> searchArtists(String query, int page, int limit) {
        Pageable pageable = PageRequest.of(Math.max(0, page - 1), limit);
        Page<Artist> pageResult = artistRepository.findByNameContainingIgnoreCaseAndPendingApprovalFalse(query, pageable);

        List<ArtistResponse> content = pageResult.getContent().stream()
                .map(ArtistResponse::from)
                .toList();

        PaginationDto pagination = new PaginationDto(page, limit, pageResult.getTotalElements(), pageResult.getTotalPages());

        return new PagedResponse<>(content, pagination);
    }
}
