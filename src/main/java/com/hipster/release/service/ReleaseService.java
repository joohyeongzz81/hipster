package com.hipster.release.service;

import com.hipster.global.dto.PagedResponse;
import com.hipster.global.dto.PaginationDto;
import com.hipster.release.domain.Release;
import com.hipster.release.dto.ReleaseSearchRequest;
import com.hipster.release.dto.ReleaseSummaryResponse;
import com.hipster.release.dto.ReleaseDetailResponse;
import com.hipster.release.dto.CreateReleaseRequest;
import com.hipster.release.repository.ReleaseRepository;
import com.hipster.moderation.service.ModerationQueueService;
import com.hipster.moderation.dto.ModerationSubmitRequest;
import com.hipster.moderation.dto.ModerationSubmitResponse;
import com.hipster.moderation.domain.EntityType;
import com.hipster.artist.repository.ArtistRepository;
import com.hipster.artist.domain.Artist;
import com.hipster.track.service.TrackService;
import com.hipster.track.dto.TrackResponse;
import com.hipster.global.exception.NotFoundException;
import com.hipster.global.exception.ErrorCode;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReleaseService {

    private final ReleaseRepository releaseRepository;
    private final ModerationQueueService moderationQueueService;
    private final ArtistRepository artistRepository;
    private final TrackService trackService;

    @Transactional
    public ModerationSubmitResponse createRelease(CreateReleaseRequest request, Long submitterId) {
        Release release = Release.builder()
                .title(request.title())
                .artistId(request.artistId())
                .genreId(request.genreId())
                .releaseType(request.releaseType())
                .releaseDate(request.releaseDate())
                .catalogNumber(request.catalogNumber())
                .label(request.label())
                .build();

        release = releaseRepository.save(release);

        ModerationSubmitRequest modRequest = new ModerationSubmitRequest(
                EntityType.RELEASE,
                release.getId(),
                request.metaComment()
        );

        return moderationQueueService.submit(modRequest, submitterId);
    }

    public ReleaseDetailResponse getReleaseDetail(Long releaseId) {
        Release release = releaseRepository.findById(releaseId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));

        String artistName = artistRepository.findById(release.getArtistId())
                .map(Artist::getName)
                .orElse("Unknown Artist");

        List<TrackResponse> tracks = trackService.getTracksByReleaseId(releaseId);

        return new ReleaseDetailResponse(
                release.getId(),
                release.getTitle(),
                release.getArtistId(),
                artistName,
                release.getReleaseType(),
                release.getReleaseDate(),
                release.getCatalogNumber(),
                release.getLabel(),
                0.0,
                0,
                tracks
        );
    }

    public PagedResponse<ReleaseSummaryResponse> searchReleases(ReleaseSearchRequest request) {
        int page = request.page() != null ? request.page() : 1;
        int limit = request.limit() != null ? request.limit() : 20;

        Sort sort = Sort.by(Sort.Direction.DESC, "releaseDate");
        if (request.sort() != null) {
            String sortProp = "release_date".equals(request.sort()) ? "releaseDate" : request.sort();
            Sort.Direction direction = "asc".equalsIgnoreCase(request.order()) ? Sort.Direction.ASC : Sort.Direction.DESC;
            sort = Sort.by(direction, sortProp);
        }

        Pageable pageable = PageRequest.of(Math.max(0, page - 1), limit, sort);

        Specification<Release> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isFalse(root.get("pendingApproval")));

            if (StringUtils.hasText(request.q())) {
                predicates.add(cb.like(cb.lower(root.get("title")), "%" + request.q().toLowerCase() + "%"));
            }

            if (request.releaseType() != null) {
                predicates.add(cb.equal(root.get("releaseType"), request.releaseType()));
            }

            if (request.year() != null) {
                predicates.add(cb.equal(cb.function("YEAR", Integer.class, root.get("releaseDate")), request.year()));
            }

            if (request.artistId() != null) {
                predicates.add(cb.equal(root.get("artistId"), request.artistId()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<Release> pageResult = releaseRepository.findAll(spec, pageable);

        List<ReleaseSummaryResponse> content = pageResult.getContent().stream()
                .map(ReleaseSummaryResponse::from)
                .toList();

        PaginationDto pagination = new PaginationDto(page, limit, pageResult.getTotalElements(), pageResult.getTotalPages());

        return new PagedResponse<>(content, pagination);
    }
}
