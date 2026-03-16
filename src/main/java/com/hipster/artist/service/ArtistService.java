package com.hipster.artist.service;

import com.hipster.artist.domain.Artist;
import com.hipster.artist.domain.ArtistStatus;
import com.hipster.artist.dto.response.ArtistResponse;
import com.hipster.artist.dto.request.CreateArtistRequest;
import com.hipster.artist.repository.ArtistRepository;
import com.hipster.global.dto.response.PagedResponse;
import com.hipster.global.dto.response.PaginationDto;
import com.hipster.global.exception.BadRequestException;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.NotFoundException;
import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.dto.request.ModerationSubmitRequest;
import com.hipster.moderation.dto.response.ModerationSubmitResponse;
import com.hipster.moderation.service.ModerationQueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ArtistService {

    private final ArtistRepository artistRepository;
    private final ModerationQueueService moderationQueueService;

    @Transactional
    public ModerationSubmitResponse createArtist(final CreateArtistRequest request, final Long submitterId) {
        final Artist artist = artistRepository.save(Artist.from(request));

        final ModerationSubmitRequest modRequest = new ModerationSubmitRequest(
                EntityType.ARTIST,
                artist.getId(),
                request.metaComment(),
                buildArtistSnapshot(request, artist));

        return moderationQueueService.submit(modRequest, submitterId);
    }

    private Map<String, Object> buildArtistSnapshot(final CreateArtistRequest request, final Artist artist) {
        final Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("artistId", artist.getId());
        snapshot.put("name", request.name());
        snapshot.put("description", request.description());
        snapshot.put("formedYear", request.formedYear());
        snapshot.put("country", request.country());
        snapshot.put("metaComment", request.metaComment());
        return snapshot;
    }

    public ArtistResponse getArtist(final Long id) {
        final Artist artist = artistRepository.findByIdAndStatusNot(id, ArtistStatus.DELETED)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ARTIST_NOT_FOUND));
        return ArtistResponse.from(artist);
    }

    public PagedResponse<ArtistResponse> searchArtists(final String query, final int page, final int limit) {
        if (limit < 1 || limit > 100) {
            throw new BadRequestException(ErrorCode.INVALID_PAGE_LIMIT);
        }
        final Pageable pageable = PageRequest.of(Math.max(0, page - 1), limit);
        final Page<Artist> pageResult = artistRepository.findByNameContainingIgnoreCaseAndStatus(query,
                ArtistStatus.ACTIVE, pageable);

        final List<ArtistResponse> content = pageResult.getContent().stream()
                .map(ArtistResponse::from)
                .toList();

        final PaginationDto pagination = new PaginationDto(page, limit, pageResult.getTotalElements(),
                pageResult.getTotalPages());

        return new PagedResponse<>(content, pagination);
    }

    @Transactional
    public void deleteArtist(final Long id) {
        final Artist artist = artistRepository.findByIdAndStatusNot(id, ArtistStatus.DELETED)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ARTIST_NOT_FOUND));
        artist.delete();
    }
}
