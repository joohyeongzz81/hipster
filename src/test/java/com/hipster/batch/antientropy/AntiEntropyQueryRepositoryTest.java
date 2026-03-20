package com.hipster.batch.antientropy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class AntiEntropyQueryRepositoryTest {

    @Mock
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Test
    @DisplayName("anti-entropy 대상 조회는 ratings 와 기존 summary release 를 함께 읽는다")
    void findAllReleaseIds_UsesRatingsAndSummaryUnion() {
        final AntiEntropyQueryRepository repository = new AntiEntropyQueryRepository(namedParameterJdbcTemplate);
        when(namedParameterJdbcTemplate.queryForList(any(String.class), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.eq(Long.class)))
                .thenReturn(List.of(11L, 22L));

        final List<Long> releaseIds = repository.findAllReleaseIds();

        final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(namedParameterJdbcTemplate)
                .queryForList(sqlCaptor.capture(), any(MapSqlParameterSource.class), org.mockito.ArgumentMatchers.eq(Long.class));

        assertThat(releaseIds).containsExactly(11L, 22L);
        assertThat(sqlCaptor.getValue()).contains("SELECT DISTINCT release_id FROM ratings");
        assertThat(sqlCaptor.getValue()).contains("SELECT release_id FROM release_rating_summary");
        assertThat(sqlCaptor.getValue()).contains("UNION");
    }

    @Test
    @DisplayName("anti-entropy 청크 재집계는 orphan summary 삭제 후 살아있는 release 를 다시 계산한다")
    void reconcileChunk_DeletesOrphanSummaryBeforeUpsert() {
        final AntiEntropyQueryRepository repository = new AntiEntropyQueryRepository(namedParameterJdbcTemplate);

        repository.reconcileChunk(List.of(11L, 22L), LocalDateTime.of(2026, 3, 19, 12, 0, 0));

        final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(namedParameterJdbcTemplate, times(2)).update(sqlCaptor.capture(), any(MapSqlParameterSource.class));

        assertThat(sqlCaptor.getAllValues()).hasSize(2);
        assertThat(sqlCaptor.getAllValues().get(0)).contains("DELETE FROM release_rating_summary");
        assertThat(sqlCaptor.getAllValues().get(0)).contains("SELECT DISTINCT release_id");
        assertThat(sqlCaptor.getAllValues().get(0)).contains("FROM ratings");

        assertThat(sqlCaptor.getAllValues().get(1)).contains("INSERT INTO release_rating_summary");
        assertThat(sqlCaptor.getAllValues().get(1)).contains("COUNT(*)");
        assertThat(sqlCaptor.getAllValues().get(1)).contains("ON DUPLICATE KEY UPDATE");
    }
}
