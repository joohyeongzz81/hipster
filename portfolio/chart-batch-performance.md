# 차트 재생성 배치 비용을 줄이기

> 차트 API를 빠르게 읽게 만든 뒤에는, 그 결과를 만드는 배치 자체가 운영 가능한 비용인지가 다음 문제였습니다.  
> 이 문서는 `release_rating_summary -> chart_scores` 재생성 경로에서 `ChartItemWriter` 병목을 분해하고, JDBC metadata fetch 최적화, `UPSERT -> STAGING_INSERT -> LIGHT_STAGE_INSERT` 비교, ES `source fetch` 병목 제거, 500만 건 full rebuild / publish 비용까지 정리한 차트 배치 성능 개선 문서입니다.

---

## 1. 문제 정의

차트 서빙 경로를 빠르게 만든 뒤에는 아래 질문에 답해야 했습니다.

- 생성 비용
  - `chart_scores` 재생성 배치는 어디가 병목인가
- 쓰기 전략
  - 본 테이블 `UPSERT`가 정말 맞는 전략인가
- 색인 비용
  - ES 재색인에서 실제로 느린 구간은 어디인가
- 최종 운영 비용
  - 500만 건 기준 full rebuild와 publish는 몇 분대인가

이 문서는 스냅샷 생성 비용을 다루고, 공개 일관성은 `chart-pipeline.md`를 다룹니다.

---

## 2. `ChartItemWriter`가 첫 번째 병목이었습니다

초기 projection 샘플 실행에서는 `chart_scores` 생성 배치에서 `write` 구간이 대부분의 시간을 차지했습니다.

| 항목 | 값 |
| --- | --- |
| 평균 청크 시간 | 약 `970ms` |
| 총 청크 수 | `2500` |
| 500만 건 projection 총 시간 추정 | 약 `40.4분` |

위 수치는 projection 샘플 실행에서 관측한 청크 기준 값이고, 총 시간 추정은 평균 청크 시간에 총 청크 수를 곱해 단순 환산한 값입니다.

이 상태에서는 조회 경로가 빨라도, 차트 재생성 자체가 비싸서 운영 가능한 구조라고 보기 어려웠습니다.

그래서 `ChartItemWriter`를 아래처럼 쪼개 보기 시작했습니다.

- `metadata fetch`
- `serialization`
- `db write`

**이 단계의 결론:** writer 병목의 중심은 `metadata fetch + upsert`였습니다.

---

## 3. JDBC metadata fetch 최적화

기존 writer는 JPA fetch join 묶음으로 메타데이터를 가져오고, Java 쪽에서 JSON을 조립하는 비용이 컸습니다.  
그래서 메타데이터 수집 경로를 `ChartReleaseMetadataQueryRepository` 단일 JDBC 집계 쿼리로 바꾸고, 장르/디스크립터/언어 JSON도 DB에서 바로 만들게 했습니다.

즉, writer 안에서 엔티티 그래프를 조립하던 책임을 줄이고, 배치 쓰기에 필요한 형태를 SQL이 직접 만들도록 옮겼습니다.

아래 수치는 projection 샘플 실행의 청크별 측정값을 평균낸 값입니다.

| 항목 | Before | After | 개선율 |
| --- | ---: | ---: | ---: |
| `metadata fetch` | `935.67ms` | `128.33ms` | `-86.3%` |
| `serialization` | `52.67ms` | `0.67ms` | `-98.7%` |
| `upsert` | `1080.33ms` | `406.00ms` | `-62.4%` |
| `total` | `2109.67ms` | `573.67ms` | `-72.8%` |

500만 건 projection 기준 단순 환산 추정도 크게 줄었습니다.

| 기준 | 평균 청크 시간 | 총 시간 추정 |
| --- | ---: | ---: |
| Before | `2109.67ms` | 약 `87.9분` |
| After | `573.67ms` | 약 `23.9분` |

이 단계의 핵심은 단순한 조회 방식 변경이 아니었습니다.

- writer 병목이 실제로 `metadata fetch + upsert`라는 점을 수치로 확인했습니다.
- 메타데이터 보강을 JDBC 집계 쿼리로 옮겨 writer 체류 시간을 크게 줄였습니다.
- JSON 직렬화 비용을 사실상 제거했습니다.

다만 이 시점에도 본 테이블 `UPSERT` 비용은 여전히 크게 남아 있었습니다.

**이 단계의 결론:** JDBC 집계 쿼리 전환으로 `metadata fetch`와 `serialization`은 크게 줄였지만, 본 테이블 `UPSERT` 자체는 여전히 다음 병목으로 남아 있었습니다.

---

## 4. `UPSERT -> STAGING_INSERT -> LIGHT_STAGE_INSERT`

다음 질문은 단순했습니다.  
정말 본 테이블 `UPSERT`가 최적해인지 다시 봐야 했습니다.

먼저 `UPSERT`와 `STAGING_INSERT`를 비교했고, 이후 더 가벼운 `LIGHT_STAGE_INSERT`까지 추가했습니다.

- `UPSERT`
  - `chart_scores` 본 테이블에 직접 `ON DUPLICATE KEY UPDATE`를 수행합니다.
- `STAGING_INSERT`
  - `chart_scores_stage_bench`에 insert-only로 적재합니다.
- `LIGHT_STAGE_INSERT`
  - 더 가벼운 `chart_scores_stage_light_bench`에 insert-only로 적재합니다.

| mode | avg db write(ms) | avg total(ms) |
| --- | ---: | ---: |
| `UPSERT` | `592.67` | `836.67` |
| `STAGING_INSERT` | `387.00` | `557.33` |
| `LIGHT_STAGE_INSERT` | `298.67` | `434.33` |

핵심 차이는 명확했습니다.

- `LIGHT_STAGE_INSERT`는 `UPSERT` 대비 평균 total을 `836.67ms -> 434.33ms`, 약 `48.1%` 줄였습니다.
- 순수 DB write 구간은 `592.67ms -> 298.67ms`, 약 `49.6%` 감소했습니다.
- `STAGING_INSERT` 대비로도 `557.33ms -> 434.33ms`, 약 `22.1%` 추가 감소했습니다.

이 비교가 중요했던 이유는 성능 때문만이 아닙니다.  
`LIGHT_STAGE_INSERT`는 `stage` 기반 스냅샷 생성 구조와 잘 맞았기 때문에, 이후 publish 전환 경계와도 자연스럽게 이어질 수 있었습니다.

**이 단계의 결론:** 차트 재생성 경로의 쓰기 전략은 본 테이블 `UPSERT`보다 `stage` 기반 insert가 더 잘 맞았고, 그중에서도 `LIGHT_STAGE_INSERT`가 가장 가벼웠습니다.

---

## 5. ES는 write보다 `source fetch`가 더 큰 병목이었습니다

projection writer를 줄이고 나니, 그다음 병목은 ES 재색인이었습니다.  
하지만 실제로 뜯어보니 ES bulk write보다 `source fetch`가 더 비쌌습니다.

기존 경로는 `PageRequest` 기반 페이지 접근과 엔티티 fetch에 가까운 방식이어서, 깊은 페이지로 갈수록 source를 가져오는 비용과 불필요한 객체 로딩 비용이 커졌습니다.

기존의 `PageRequest + JPA entity fetch` 대신 `JDBC + keyset(id > ?)` 기반으로 source fetch 경로를 바꾼 뒤, `refresh_interval = -1` 전략과 `bulkIndex(IndexQuery)`까지 적용했습니다.

| 단계 | fetch(ms) | index(ms) | total(ms) |
| --- | ---: | ---: | ---: |
| 초기 샘플 | `6854.33` | `571.67` | `7455.67` |
| keyset / JDBC 전환 후 | `81.00` | `1493.00` | `1663.00` |
| `bulkIndex` 최적화 후 | `44.00` | `806.33` | `953.67` |

이 단계에서 얻은 결론은 두 가지였습니다.

- ES가 느린 줄 알았지만, 실제로는 source를 가져오는 방식이 더 큰 문제였습니다.
- `fetch -> index -> refresh`를 분해해서 보지 않으면 병목을 잘못 진단하기 쉽습니다.

`index(ms)`가 keyset/JDBC 전환 직후 더 크게 보인 것도 같은 맥락이었습니다. fetch 지배 구간이 사라지면서 기존에 가려져 있던 bulk index 비용이 더 또렷하게 드러난 것입니다.

즉, ES 개선의 핵심은 "검색 엔진을 붙였다"가 아니라, **source fetch 병목을 제거하고 bulk index 비용만 남도록 병목의 위치를 옮긴 것**이었습니다.

**이 단계의 결론:** ES 단계의 핵심 문제는 검색 엔진 자체보다 `source fetch`였고, keyset/JDBC 전환 이후에는 bulk index가 다음 최적화 대상으로 분명하게 드러났습니다.

---

## 6. full rebuild / publish 비용

최종적으로는 샘플 추정이 아니라 full run 실측으로 비용을 확인했습니다.

| 항목 | 값 |
| --- | ---: |
| 500만 건 full rebuild baseline | `11.7859분` |
| publish end-to-end | `13.0317분` |

여기서 중요한 점은 두 수치의 의미가 다르다는 것입니다.

- `11.7859분`은 `runFullBatchBenchmark()` 기준으로 projection 전체 생성, ES full rebuild, synthetic cache eviction까지 포함한 full rebuild baseline입니다.
- `13.0317분`은 실제 `chartUpdateJob`을 끝까지 실행한 publish end-to-end 시간입니다.

즉, rebuild 비용과 publish 비용을 같은 값으로 말하면 안 됩니다.  
배치를 빠르게 만드는 문제와 스냅샷을 실제로 공개하는 문제는 분리해서 봐야 했고, 공개 일관성은 별도 publish 경계에서 다뤘습니다.

**이 단계의 결론:** `11.7859분`과 `13.0317분`은 서로 다른 경계를 가진 실측값이고, rebuild 비용과 publish 비용은 분리해서 봐야 했습니다.

---

## 7. 관련 주제 경계

이 문서와 맞물리는 관련 주제는 아래 문서에서 이어집니다.

- `ratings -> release_rating_summary` 실시간 집계와 Anti-Entropy
- `users.weighting_score` 계산 배치
- `chart_publish_state`, 공개 버전 식별자, 롤백 계약
- 차트 API 조회 경로 자체의 조회 병목

즉, 이 문서는 차트 조회 모델을 **만드는 비용** 문서이고, 공통 집계 계층, 공개 일관성, API 서빙은 각각 별도 문서가 맡습니다.

---

## 8. 관련 코드

아래 코드는 이 문서의 핵심 흐름을 실제로 구현한 파일들입니다.

- `src/main/java/com/hipster/batch/chart/step/ChartItemWriter.java`
- `src/main/java/com/hipster/batch/chart/repository/ChartReleaseMetadataQueryRepository.java`
- `src/main/java/com/hipster/batch/chart/repository/ChartScoreQueryRepository.java`
- `src/main/java/com/hipster/batch/chart/benchmark/ChartProjectionWriteMode.java`
- `src/main/java/com/hipster/batch/chart/benchmark/service/ChartBatchBenchmarkService.java`
- `src/main/java/com/hipster/batch/chart/benchmark/service/ChartBatchRunService.java`
- `src/main/java/com/hipster/chart/repository/ChartScoreIndexSourceQueryRepository.java`
- `src/main/java/com/hipster/chart/service/ChartElasticsearchIndexService.java`

---

## 9. 관련 문서

아래 문서는 이 배치와 맞닿은 관련 문서입니다.

- [평점 조회와 차트 배치가 함께 쓰는 공통 집계 계층 만들기](./rating-aggregation.md)
- [차트 공개 일관성을 위해 publish 파이프라인 다시 세우기](./chart-pipeline.md)
- [차트 API 읽기 경로를 캐시·검색·fallback·메타데이터로 분리해 응답 병목 줄이기](./chart-serving.md)

---

## 10. 한 줄 요약

차트 배치 재생성 경로를 `metadata fetch`, 쓰기 방식, ES `source fetch` 단계로 분해하고, JDBC 집계 쿼리와 `stage` 기반 쓰기 전략으로 500만 건 재생성 비용을 운영 가능한 수준까지 낮췄습니다.
