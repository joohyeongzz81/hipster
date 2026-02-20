package com.hipster.genre.service;

import com.hipster.genre.domain.Genre;
import com.hipster.genre.dto.request.CreateGenreRequest;
import com.hipster.genre.dto.response.GenreNodeResponse;
import com.hipster.genre.repository.GenreRepository;
import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.dto.request.ModerationSubmitRequest;
import com.hipster.moderation.dto.response.ModerationSubmitResponse;
import com.hipster.moderation.service.ModerationQueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GenreService {

    private final GenreRepository genreRepository;
    private final ModerationQueueService moderationQueueService;

    @Transactional
    public ModerationSubmitResponse createGenre(final CreateGenreRequest request, final Long submitterId) {
        final Genre genre = genreRepository.save(Genre.builder()
                .name(request.name())
                .parentId(request.parentId())
                .description(request.description())
                .isDescriptor(request.isDescriptor())
                .build());

        final ModerationSubmitRequest modRequest = new ModerationSubmitRequest(
                EntityType.GENRE,
                genre.getId(),
                request.metaComment()
        );

        return moderationQueueService.submit(modRequest, submitterId);
    }

    public List<GenreNodeResponse> getGenreTree() {
        final List<Genre> allGenres = genreRepository.findAllByPendingApprovalFalse();
        final Map<Long, GenreNodeResponse> nodeMap = buildNodeMap(allGenres);
        return assembleTree(allGenres, nodeMap);
    }

    private Map<Long, GenreNodeResponse> buildNodeMap(final List<Genre> genres) {
        final Map<Long, GenreNodeResponse> nodeMap = new HashMap<>();
        for (final Genre genre : genres) {
            nodeMap.put(genre.getId(), GenreNodeResponse.from(genre));
        }
        return nodeMap;
    }

    private List<GenreNodeResponse> assembleTree(final List<Genre> genres,
                                                  final Map<Long, GenreNodeResponse> nodeMap) {
        final List<GenreNodeResponse> roots = new ArrayList<>();
        for (final Genre genre : genres) {
            final GenreNodeResponse node = nodeMap.get(genre.getId());
            if (genre.getParentId() == null) {
                roots.add(node);
            } else {
                final GenreNodeResponse parent = nodeMap.get(genre.getParentId());
                if (parent != null) {
                    parent.children().add(node);
                }
            }
        }
        return roots;
    }
}
