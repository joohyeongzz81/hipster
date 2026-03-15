package com.hipster.batch.chart;

import com.hipster.batch.chart.dto.ChartScoreDto;
import com.hipster.batch.chart.repository.ChartScoreQueryRepository;
import com.hipster.chart.config.ChartAlgorithmProperties;
import com.hipster.rating.domain.ReleaseRatingSummary;
import com.hipster.release.repository.ReleaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hipster.release.domain.Release;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StopWatch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 3구간(초반 / 중반 / 후반) 샘플 처리 시간을 측정하여
 * 500만 건 전체 배치 소요 시간을 추정하는 테스트.
 *
 * <p>측정 방식
 * <pre>
 *   구간 A  : id 범위 최솟값     ~ 최솟값 + SAMPLE_SIZE (버퍼 풀 콜드 스타트 구간)
 *   구간 B  : id 범위 중간       ~ 중간   + SAMPLE_SIZE (정상 처리 구간)
 *   구간 C  : id 범위 최댓값 전  ~ 최댓값  (디스크 I/O 병목 구간)
 *   평균 throughput(rows/sec) 으로 전체 TOTAL_ROWS 외삽
 * </pre>
 *
 * <p>실행 전 전제
 * <ul>
 *   <li>release_rating_summary 테이블에 약 500만 건이 존재해야 한다.</li>
 *   <li>@ActiveProfiles("local") 환경의 DB에 접근 가능해야 한다.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.batch.job.enabled=false",
        "spring.batch.jdbc.initialize-schema=always"
})
@ActiveProfiles("local")
class ChartBatchThroughputEstimationTest {

    private static final Logger log = LoggerFactory.getLogger(ChartBatchThroughputEstimationTest.class);

    /** 구간당 처리할 행 수. chunk 단위(2000)의 배수로 설정. */
    private static final int SAMPLE_SIZE = 100_000;

    /** 외삽 기준이 되는 전체 데이터 건수. */
    private static final long TOTAL_ROWS = 5_000_000L;

    /** chunk 크기. ChartJobConfig.CHUNK_SIZE 와 동일하게 유지. */
    private static final int CHUNK_SIZE = 2000;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ChartScoreQueryRepository chartScoreQueryRepository;
    @Autowired private ReleaseRepository releaseRepository;
    @Autowired private ChartAlgorithmProperties chartAlgorithmProperties;
    @Autowired private ObjectMapper objectMapper;
    /**
     * 청크 처리 시 Hibernate Session을 유지하기 위한 TransactionTemplate.
     * Spring Batch 청크 트랜잭션과 동일한 효과를 재현한다.
     */
    @Autowired private TransactionTemplate transactionTemplate;

    /** Step 1 Tasklet 결과값 — 테스트에서 직접 계산 */
    private BigDecimal globalWeightedAverage;

    @BeforeEach
    void setUp() {
        // Step 1 (Tasklet) 로직을 인라인 재현: 글로벌 가중 평균 C 계산
        globalWeightedAverage = jdbcTemplate.queryForObject(
                "SELECT CASE WHEN SUM(weighted_count_sum) = 0 THEN 0 " +
                "ELSE SUM(weighted_score_sum) / SUM(weighted_count_sum) END " +
                "FROM release_rating_summary",
                BigDecimal.class
        );
        if (globalWeightedAverage == null) {
            globalWeightedAverage = chartAlgorithmProperties.getGlobalAvgFallback();
        }
        log.info("[ESTIMATION] 글로벌 가중 평균 C = {}", globalWeightedAverage);
    }

    @Test
    @DisplayName("3구간 샘플 처리 시간 측정으로 500만 건 전체 배치 소요 시간 추정")
    void estimateTotalBatchDurationByThreeSegments() {
        // ── 전체 id 범위 조회 ──────────────────────────────────────────────
        long minId = requireNonNull(jdbcTemplate.queryForObject(
                "SELECT MIN(id) FROM release_rating_summary", Long.class), "MIN(id)");
        long maxId = requireNonNull(jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM release_rating_summary", Long.class), "MAX(id)");
        long midId = (minId + maxId) / 2;

        log.info("[ESTIMATION] id 범위: {} ~ {} / 중간점: {}", minId, maxId, midId);

        // ── 구간 정의 ─────────────────────────────────────────────────────
        //   구간 A: 초반 (콜드 스타트)
        //   구간 B: 중반 (워밍업 후 정상 처리)
        //   구간 C: 후반 (디스크 I/O 병목 구간)
        long[] segmentStartIds = { minId, midId, maxId - idSpanForRows(maxId - midId) };
        String[] segmentNames  = { "구간 A (초반)", "구간 B (중반)", "구간 C (후반)" };

        long[] segmentElapsedMs = new long[3];

        printHeader();

        for (int i = 0; i < 3; i++) {
            long startId = segmentStartIds[i];
            long elapsedMs = measureSegment(segmentNames[i], startId);
            segmentElapsedMs[i] = elapsedMs;

            double throughput = (double) SAMPLE_SIZE / (elapsedMs / 1000.0);
            log.info("[ESTIMATION] {} 완료: {}ms / throughput = {} rows/sec",
                    segmentNames[i], elapsedMs, String.format("%.1f", throughput));
        }

        // ── 평균 throughput으로 전체 시간 외삽 ───────────────────────────
        double avgElapsedMs = (segmentElapsedMs[0] + segmentElapsedMs[1] + segmentElapsedMs[2]) / 3.0;
        double avgThroughputPerSec = (double) SAMPLE_SIZE / (avgElapsedMs / 1000.0);
        double estimatedTotalSec   = TOTAL_ROWS / avgThroughputPerSec;
        double estimatedTotalMin   = estimatedTotalSec / 60.0;

        printResult(segmentNames, segmentElapsedMs, avgThroughputPerSec, estimatedTotalSec, estimatedTotalMin);
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────

    /**
     * 지정한 구간의 SAMPLE_SIZE 건을 실제 Processor + Writer 로직으로 처리하고
     * 소요 시간(ms)을 반환한다.
     */
    private long measureSegment(String segmentName, long startId) {
        log.info("[ESTIMATION] {} 측정 시작 (startId={})", segmentName, startId);

        List<ReleaseRatingSummary> rows = fetchSegment(startId);

        StopWatch sw = new StopWatch();
        sw.start();

        // CHUNK_SIZE 단위로 분할하여 Processor → Writer 파이프라인 재현.
        // transactionTemplate.execute()로 감싸서 Spring Batch 청크 트랜잭션을 재현한다:
        // 세 개의 findReleasesWith* 쿼리가 동일한 Hibernate Session을 공유해야
        // releaseDescriptors / releaseLanguages 지연 컬렉션이 올바르게 초기화된다.
        for (int offset = 0; offset < rows.size(); offset += CHUNK_SIZE) {
            List<ReleaseRatingSummary> chunk =
                    rows.subList(offset, Math.min(offset + CHUNK_SIZE, rows.size()));

            List<ChartScoreDto> processed = chunk.stream()
                    .map(this::applyProcessor)
                    .collect(Collectors.toList());

            transactionTemplate.execute(status -> {
                applyWriter(processed);
                return null;
            });
        }

        sw.stop();
        return sw.getTotalTimeMillis();
    }

    /**
     * release_rating_summary 에서 startId 이후 SAMPLE_SIZE 건을 읽는다.
     * JdbcPagingItemReader 의 "id ASC 페이징" 방식을 JdbcTemplate 으로 재현.
     */
    private List<ReleaseRatingSummary> fetchSegment(long startId) {
        return jdbcTemplate.query(
                "SELECT id, release_id, total_rating_count, average_score, " +
                "weighted_score_sum, weighted_count_sum, batch_synced_at, updated_at " +
                "FROM release_rating_summary " +
                "WHERE id >= ? ORDER BY id LIMIT ?",
                (rs, rowNum) -> {
                    ReleaseRatingSummary summary = new ReleaseRatingSummary(rs.getLong("release_id"));
                    summary.recalculate(
                            rs.getLong("total_rating_count"),
                            rs.getDouble("average_score"),
                            rs.getBigDecimal("weighted_score_sum"),
                            rs.getBigDecimal("weighted_count_sum")
                    );
                    return summary;
                },
                startId, SAMPLE_SIZE
        );
    }

    /**
     * ChartItemProcessor.process() 로직을 인라인 재현.
     * BayesianScoreCalculator 호출 비용을 포함한다.
     */
    private ChartScoreDto applyProcessor(ReleaseRatingSummary summary) {
        com.hipster.chart.algorithm.BayesianScoreCalculator calculator =
                new com.hipster.chart.algorithm.BayesianScoreCalculator(
                        chartAlgorithmProperties.getPriorWeightM(),
                        chartAlgorithmProperties.getEsotericMultiplierK()
                );
        com.hipster.chart.algorithm.BayesianResult result = calculator.calculate(
                summary.getWeightedScoreSum(),
                summary.getWeightedCountSum(),
                globalWeightedAverage
        );
        double weightedAvgRating = summary.getWeightedCountSum().signum() > 0
                ? summary.getWeightedScoreSum()
                        .divide(summary.getWeightedCountSum(), 10, RoundingMode.HALF_UP)
                        .doubleValue()
                : 0.0;

        return new ChartScoreDto(
                summary.getReleaseId(),
                result.score().doubleValue(),
                weightedAvgRating,
                summary.getWeightedCountSum().doubleValue(),
                summary.getTotalRatingCount(),
                result.isEsoteric(),
                null, null, null, null, null, null
        );
    }

    /**
     * ChartItemWriter.write() 로직을 인라인 재현.
     * Release IN 조회 3회 + bulkUpsert 비용을 포함한다.
     */
    private void applyWriter(List<ChartScoreDto> items) {
        if (items.isEmpty()) return;

        List<Long> releaseIds = items.stream().map(ChartScoreDto::releaseId).toList();

        List<Release> releases = releaseRepository.findReleasesWithGenres(releaseIds);
        if (!releases.isEmpty()) {
            releaseRepository.findReleasesWithDescriptors(releaseIds);
            releaseRepository.findReleasesWithLanguages(releaseIds);
        }

        Map<Long, Release> releaseMap = releases.stream()
                .collect(Collectors.toMap(Release::getId, r -> r));

        List<ChartScoreDto> enriched = items.stream().map(dto -> {
            Release release = releaseMap.get(dto.releaseId());
            if (release == null) return dto;

            String genreIdsJson = null, descriptorIdsJson = null, languagesJson = null;
            try {
                List<Map<String, Object>> genreData = release.getReleaseGenres().stream()
                        .map(rg -> {
                            Map<String, Object> m = new HashMap<>();
                            m.put("id", rg.getGenre().getId());
                            m.put("isPrimary", rg.getIsPrimary());
                            return m;
                        }).collect(Collectors.toList());
                if (!genreData.isEmpty()) genreIdsJson = objectMapper.writeValueAsString(genreData);

                List<Long> descIds = release.getReleaseDescriptors().stream()
                        .map(rd -> rd.getDescriptor().getId()).collect(Collectors.toList());
                if (!descIds.isEmpty()) descriptorIdsJson = objectMapper.writeValueAsString(descIds);

                List<String> langs = release.getReleaseLanguages().stream()
                        .map(rl -> rl.getLanguage().name()).collect(Collectors.toList());
                if (!langs.isEmpty()) languagesJson = objectMapper.writeValueAsString(langs);

            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.error("[ESTIMATION] JSON 직렬화 실패 releaseId={}", dto.releaseId(), e);
            }

            Integer releaseYear = release.getReleaseDate() != null
                    ? release.getReleaseDate().getYear() : null;

            return new ChartScoreDto(
                    dto.releaseId(), dto.bayesianScore(), dto.weightedAvgRating(),
                    dto.effectiveVotes(), dto.totalRatings(), dto.isEsoteric(),
                    genreIdsJson, release.getReleaseType(), releaseYear,
                    descriptorIdsJson, release.getLocationId(), languagesJson
            );
        }).collect(Collectors.toList());

        chartScoreQueryRepository.bulkUpsertChartScores(enriched);
    }

    /**
     * 후반 구간 startId 계산 시, 실제 SAMPLE_SIZE 건이 포함되는 id 범위를 추산.
     * id 가 연속적이지 않을 수 있으므로 여유 있게 절반 범위만 사용.
     */
    private long idSpanForRows(long halfIdRange) {
        // 보수적으로 전체 id 범위의 25%를 후반 구간 버퍼로 사용
        return halfIdRange / 2;
    }

    private static <T> T requireNonNull(T value, String label) {
        if (value == null) throw new IllegalStateException(label + " 조회 결과가 null입니다.");
        return value;
    }

    private void printHeader() {
        log.info("=========================================================");
        log.info("  배치 소요 시간 추정 테스트 시작");
        log.info("  샘플 건수: {} 건/구간 | 외삽 기준: {} 만 건 | 청크 크기: {}",
                SAMPLE_SIZE, TOTAL_ROWS / 10_000, CHUNK_SIZE);
        log.info("=========================================================");
    }

    private void printResult(String[] names, long[] elapsedMs,
                             double avgThroughput, double totalSec, double totalMin) {
        log.info("=========================================================");
        log.info("  [측정 결과]");
        for (int i = 0; i < names.length; i++) {
            log.info("  {} : {}ms ({} rows/sec)",
                    names[i], elapsedMs[i],
                    String.format("%.1f", (double) SAMPLE_SIZE / (elapsedMs[i] / 1000.0)));
        }
        log.info("---------------------------------------------------------");
        log.info("  평균 처리 속도 : {} rows/sec", String.format("%.1f", avgThroughput));
        log.info("  {} 만 건 추정 소요 시간 : {}초 ({}분)",
                TOTAL_ROWS / 10_000,
                String.format("%.1f", totalSec),
                String.format("%.1f", totalMin));
        log.info("=========================================================");

        // assert 없음: 이 테스트는 수치 측정이 목적이며 통과/실패 기준이 없다.
    }
}
