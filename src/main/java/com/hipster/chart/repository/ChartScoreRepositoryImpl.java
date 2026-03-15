package com.hipster.chart.repository;

import com.hipster.chart.domain.ChartScore;
import com.hipster.chart.domain.QChartScore;
import com.hipster.chart.dto.request.ChartFilterRequest;
import com.hipster.global.domain.Language;
import com.hipster.release.domain.ReleaseType;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.hipster.chart.domain.QChartScore.chartScore;

@Repository
@RequiredArgsConstructor
public class ChartScoreRepositoryImpl implements ChartScoreRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<ChartScore> findChartsDynamic(ChartFilterRequest filter, Pageable pageable) {
        final List<Long> genreIds = filter.normalizedGenreIds();
        return queryFactory
                .selectFrom(chartScore)
                .where(
                        isEsotericEq(filter.includeEsoteric()),
                        genreIdsContainAll(genreIds),
                        descriptorIdContains(filter.descriptorId()),
                        locationIdEq(filter.locationId()),
                        languageContains(filter.language()),
                        releaseTypeEq(filter.releaseType()),
                        releaseYearEq(filter.year())
                )
                .orderBy(chartScore.bayesianScore.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
    }

    private BooleanExpression isEsotericEq(Boolean includeEsoteric) {
        if (Boolean.TRUE.equals(includeEsoteric)) {
            return null;
        }
        return chartScore.isEsoteric.eq(false);
    }

    private BooleanExpression genreIdsContainAll(List<Long> genreIds) {
        if (genreIds == null || genreIds.isEmpty()) {
            return null;
        }

        BooleanExpression combined = null;
        for (Long genreId : genreIds) {
            final BooleanExpression containsGenre = Expressions.booleanTemplate(
                    "function('json_contains', {0}, function('json_object', 'id', {1})) = true",
                    chartScore.genreIds, genreId
            );
            combined = combined == null ? containsGenre : combined.and(containsGenre);
        }
        return combined;
    }

    private BooleanExpression descriptorIdContains(Long descriptorId) {
        if (descriptorId == null) {
            return null;
        }
        return Expressions.booleanTemplate(
                "function('json_contains', {0}, {1}) = true",
                chartScore.descriptorIds, String.valueOf(descriptorId)
        );
    }

    private BooleanExpression locationIdEq(Long locationId) {
        return locationId != null ? chartScore.locationId.eq(locationId) : null;
    }

    private BooleanExpression languageContains(Language language) {
        if (language == null) {
            return null;
        }
        return Expressions.booleanTemplate(
                "function('json_contains', {0}, {1}) = true",
                chartScore.languages, "\"" + language.name() + "\""
        );
    }

    private BooleanExpression releaseTypeEq(ReleaseType releaseType) {
        return releaseType != null ? chartScore.releaseType.eq(releaseType) : null;
    }

    private BooleanExpression releaseYearEq(Integer year) {
        return year != null ? chartScore.releaseYear.eq(year) : null;
    }
}
