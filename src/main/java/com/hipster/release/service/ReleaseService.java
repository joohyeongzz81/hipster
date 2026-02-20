package com.hipster.release.service;

import com.hipster.artist.domain.Artist;
import com.hipster.artist.repository.ArtistRepository;
import com.hipster.global.dto.response.PagedResponse;
import com.hipster.global.dto.response.PaginationDto;
import com.hipster.global.exception.ErrorCode;
import com.hipster.global.exception.NotFoundException;
import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.dto.request.ModerationSubmitRequest;
import com.hipster.moderation.dto.response.ModerationSubmitResponse;
import com.hipster.moderation.service.ModerationQueueService;
import com.hipster.release.domain.Release;
import com.hipster.release.dto.request.CreateReleaseRequest;
import com.hipster.release.dto.response.ReleaseDetailResponse;
import com.hipster.release.dto.request.ReleaseSearchRequest;
import com.hipster.release.dto.response.ReleaseSummaryResponse;
import com.hipster.release.repository.ReleaseRepository;
import com.hipster.track.dto.response.TrackResponse;
import com.hipster.track.service.TrackService;
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
    public ModerationSubmitResponse createRelease(final CreateReleaseRequest request, final Long submitterId) {
        final Release release = releaseRepository.save(Release.builder()
                .title(request.title())
                .artistId(request.artistId())
                .genreId(request.genreId())
                .releaseType(request.releaseType())
                .releaseDate(request.releaseDate())
                .catalogNumber(request.catalogNumber())
                .label(request.label())
                .build());

        final ModerationSubmitRequest modRequest = new ModerationSubmitRequest(
                EntityType.RELEASE,
                release.getId(),
                request.metaComment()
        );

        return moderationQueueService.submit(modRequest, submitterId);
    }

    public ReleaseDetailResponse getReleaseDetail(final Long releaseId) {
        final Release release = releaseRepository.findById(releaseId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.RELEASE_NOT_FOUND));

        final String artistName = artistRepository.findById(release.getArtistId())
                .map(Artist::getName)
                .orElse("Unknown Artist");

        final List<TrackResponse> tracks = trackService.getTracksByReleaseId(releaseId);

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

    public PagedResponse<ReleaseSummaryResponse> searchReleases(final ReleaseSearchRequest request) {
        final int page = request.page() != null ? request.page() : 1;
        final int limit = request.limit() != null ? request.limit() : 20;

        final Sort sort = buildSort(request);
        final Pageable pageable = PageRequest.of(Math.max(0, page - 1), limit, sort);
        final Specification<Release> spec = buildSearchSpecification(request);

        final Page<Release> pageResult = releaseRepository.findAll(spec, pageable);

        final List<ReleaseSummaryResponse> content = pageResult.getContent().stream()
                .map(ReleaseSummaryResponse::from)
                .toList();

        final PaginationDto pagination = new PaginationDto(page, limit, pageResult.getTotalElements(), pageResult.getTotalPages());

        return new PagedResponse<>(content, pagination);
    }

    private Sort buildSort(final ReleaseSearchRequest request) {
        if (request.sort() == null) {
            return Sort.by(Sort.Direction.DESC, "releaseDate");
        }
        final String sortProp = "release_date".equals(request.sort()) ? "releaseDate" : request.sort();
        final Sort.Direction direction = "asc".equalsIgnoreCase(request.order()) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(direction, sortProp);
    }

    private Specification<Release> buildSearchSpecification(final ReleaseSearchRequest request) {
        return (root, query, cb) -> {
            final List<Predicate> predicates = new ArrayList<>();
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
    }
}
