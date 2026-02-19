package com.hipster.genre.service;

import com.hipster.genre.domain.Genre;
import com.hipster.genre.dto.CreateGenreRequest;
import com.hipster.genre.dto.GenreNodeResponse;
import com.hipster.genre.repository.GenreRepository;
import com.hipster.moderation.domain.EntityType;
import com.hipster.moderation.dto.ModerationSubmitRequest;
import com.hipster.moderation.dto.ModerationSubmitResponse;
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
    public ModerationSubmitResponse createGenre(CreateGenreRequest request, Long submitterId) {
        // Parent validation logic could be added here

        Genre genre = Genre.builder()
                .name(request.name())
                .parentId(request.parentId())
                .description(request.description())
                .isDescriptor(request.isDescriptor())
                // level and path logic should be handled here or after approval
                .build();

        genre = genreRepository.save(genre);

        ModerationSubmitRequest modRequest = new ModerationSubmitRequest(
                EntityType.GENRE,
                genre.getId(),
                request.metaComment()
        );

        return moderationQueueService.submit(modRequest, submitterId);
    }

    public List<GenreNodeResponse> getGenreTree() {
        List<Genre> allGenres = genreRepository.findAllByPendingApprovalFalse();
        Map<Long, GenreNodeResponse> nodeMap = new HashMap<>();
        List<GenreNodeResponse> roots = new ArrayList<>();

        // 1. Create nodes
        for (Genre genre : allGenres) {
            nodeMap.put(genre.getId(), GenreNodeResponse.from(genre));
        }

        // 2. Build tree
        for (Genre genre : allGenres) {
            GenreNodeResponse node = nodeMap.get(genre.getId());
            if (genre.getParentId() == null) {
                roots.add(node);
            } else {
                GenreNodeResponse parent = nodeMap.get(genre.getParentId());
                if (parent != null) {
                    parent.children().add(node);
                }
            }
        }

        return roots;
    }
}
