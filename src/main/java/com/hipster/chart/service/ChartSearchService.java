package com.hipster.chart.service;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.hipster.chart.domain.ChartDocument;
import com.hipster.chart.dto.request.ChartFilterRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChartSearchService {

    private final ElasticsearchOperations elasticsearchOperations;
    private final ChartElasticsearchIndexService chartElasticsearchIndexService;

    public List<Long> searchReleaseIds(final ChartFilterRequest filter, final int page, final int size) {
        final BoolQuery.Builder boolQueryBuilder = new BoolQuery.Builder();
        boolean hasFilter = false;

        if (!Boolean.TRUE.equals(filter.includeEsoteric())) {
            boolQueryBuilder.filter(f -> f.term(t -> t
                    .field("isEsoteric")
                    .value(FieldValue.of(false))
            ));
            hasFilter = true;
        }

        if (filter.hasGenreFilter()) {
            for (Long genreId : filter.normalizedGenreIds()) {
                boolQueryBuilder.filter(f -> f.term(t -> t
                        .field("genreIds")
                        .value(FieldValue.of(Math.toIntExact(genreId)))
                ));
                hasFilter = true;
            }
        }

        if (filter.descriptorId() != null) {
            boolQueryBuilder.filter(f -> f.term(t -> t
                    .field("descriptorIds")
                    .value(FieldValue.of(Math.toIntExact(filter.descriptorId())))
            ));
            hasFilter = true;
        }

        if (filter.locationId() != null) {
            boolQueryBuilder.filter(f -> f.term(t -> t
                    .field("locationId")
                    .value(FieldValue.of(filter.locationId()))
            ));
            hasFilter = true;
        }

        if (filter.language() != null) {
            boolQueryBuilder.filter(f -> f.term(t -> t
                    .field("languages")
                    .value(FieldValue.of(filter.language().name()))
            ));
            hasFilter = true;
        }

        if (filter.releaseType() != null) {
            boolQueryBuilder.filter(f -> f.term(t -> t
                    .field("releaseType")
                    .value(FieldValue.of(filter.releaseType().name()))
            ));
            hasFilter = true;
        }

        if (filter.year() != null) {
            boolQueryBuilder.filter(f -> f.term(t -> t
                    .field("releaseYear")
                    .value(FieldValue.of(filter.year()))
            ));
            hasFilter = true;
        }

        final Query query = hasFilter
                ? Query.of(q -> q.bool(boolQueryBuilder.build()))
                : Query.of(q -> q.matchAll(m -> m));

        final NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(query)
                .withSort(Sort.by(Sort.Direction.DESC, "bayesianScore"))
                .withPageable(PageRequest.of(page, size))
                .build();

        final SearchHits<ChartDocument> searchHits = elasticsearchOperations.search(
                nativeQuery,
                ChartDocument.class,
                IndexCoordinates.of(resolveSearchIndexName())
        );

        return searchHits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .map(ChartDocument::getReleaseId)
                .collect(Collectors.toList());
    }

    private String resolveSearchIndexName() {
        return chartElasticsearchIndexService.resolvePublishedIndexName();
    }
}
