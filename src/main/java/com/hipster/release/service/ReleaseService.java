package com.hipster.release.service;

import com.hipster.global.dto.PagedResponse;
import com.hipster.global.dto.PaginationDto;
import com.hipster.release.domain.Release;
import com.hipster.release.dto.ReleaseSearchRequest;
import com.hipster.release.dto.ReleaseSummaryResponse;
import com.hipster.release.repository.ReleaseRepository;
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
