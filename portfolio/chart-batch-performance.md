# 차트 재생성 배치 비용을 줄이기

> 차트 조회를 빠르게 만든 뒤에는, 그 결과를 만드는 재생성 배치 자체가 운영 가능한 비용인지가 다음 문제였습니다.  
> 이 문서는 `release_rating_summary -> chart_scores` 재생성 경로를 writer, 쓰기 방식, ES `source fetch`로 나눠 실제 병목과 최종 비용을 확인한 기록입니다.

---

## 1. 문제 정의

차트 공개 일관성은 `chart-pipeline.md`가 맡고, 여기서는 스냅샷 생성 비용만 봤습니다.

이 문서에서 답하려던 질문은 네 가지였습니다.

- `chart_scores` 재생성 배치에서 실제 병목은 어디인가
- 본 테이블 `UPSERT`가 정말 맞는 쓰기 전략인가
- ES 재색인에서 느린 구간은 어디인가
- 500만 건 기준 `full rebuild`와 실제 publish job은 각각 몇 분대인가

핵심은 "차트 배치를 튜닝했다"가 아니라, **차트 결과를 만드는 비용을 어디서 줄였는지 근거를 남기는 것**이었습니다.

---

## 2. 첫 병목은 writer와 `metadata fetch`였습니다

초기 projection 샘플 실행에서는 `chart_scores` 생성 배치 시간이 대부분 writer에 몰려 있었습니다.

| 항목 | 값 |
| --- | ---: |
| 평균 청크 시간 | 약 `970ms` |
| 총 청크 수 | `2500` |
| 500만 건 projection 총 시간 추정 | 약 `40.4분` |

위 값은 projection 샘플 실행의 청크 기준 값이고, 총 시간 추정은 평균 청크 시간에 총 청크 수를 곱해 단순 환산한 값입니다.

그래서 `ChartItemWriter`를 `metadata fetch`, `serialization`, `db write`로 나눠 봤습니다.  
기존에는 JPA fetch join으로 메타데이터를 읽고 Java 쪽에서 JSON을 조립했는데, 이를 JDBC 집계 쿼리로 바꿔 차트 쓰기에 필요한 형태를 SQL이 바로 만들게 했습니다.

| 항목 | Before | After | 개선율 |
| --- | ---: | ---: | ---: |
| `metadata fetch` | `935.67ms` | `128.33ms` | `-86.3%` |
| `serialization` | `52.67ms` | `0.67ms` | `-98.7%` |
| `upsert` | `1080.33ms` | `406.00ms` | `-62.4%` |
| `total` | `2109.67ms` | `573.67ms` | `-72.8%` |

500만 건 projection 기준 단순 환산도 `약 87.9분 -> 약 23.9분`까지 줄었습니다.

**이 단계의 결론:** writer 병목의 핵심은 `metadata fetch + upsert`였고, 메타데이터 조립 책임을 JDBC 집계 쿼리로 옮기면서 첫 번째 큰 병목을 줄였습니다.

---

## 3. 본 테이블 `UPSERT`보다 `stage` 기반 insert가 더 맞았습니다

메타데이터 비용을 줄인 뒤에도 본 테이블 `UPSERT`는 여전히 크게 남아 있었습니다.  
그래서 쓰기 전략 자체를 다시 비교했습니다.

- `UPSERT`
  - `chart_scores` 본 테이블에 직접 `ON DUPLICATE KEY UPDATE`
- `STAGING_INSERT`
  - `chart_scores_stage_bench`에 insert-only 적재
- `LIGHT_STAGE_INSERT`
  - 더 가벼운 `chart_scores_stage_light_bench`에 insert-only 적재

| mode | avg db write(ms) | avg total(ms) |
| --- | ---: | ---: |
| `UPSERT` | `592.67` | `836.67` |
| `STAGING_INSERT` | `387.00` | `557.33` |
| `LIGHT_STAGE_INSERT` | `298.67` | `434.33` |

`LIGHT_STAGE_INSERT`는 `UPSERT` 대비 평균 총 시간을 `836.67ms -> 434.33ms`, 약 `48.1%` 줄였습니다.  
순수 DB write 구간도 `592.67ms -> 298.67ms`, 약 `49.6%` 감소했습니다.

이 선택은 성능뿐 아니라, 후보 결과를 먼저 쌓고 마지막에만 공개 테이블을 바꾸는 `stage` 기반 스냅샷 생성 구조와도 잘 맞았습니다.

**이 단계의 결론:** 차트 재생성 경로는 본 테이블 직접 `UPSERT`보다 `stage` 기반 insert가 더 잘 맞았고, 그중 `LIGHT_STAGE_INSERT`가 가장 가벼웠습니다.

---

## 4. ES 재색인의 핵심 병목은 write보다 `source fetch`였습니다

projection writer를 줄이고 나니 다음 병목은 ES 재색인이었습니다.  
하지만 실제로는 ES bulk write보다 `source fetch`가 더 비쌌습니다.

기존 경로는 `PageRequest` 기반 페이지 접근과 엔티티 fetch에 가까워, 깊은 페이지로 갈수록 source를 가져오는 비용과 불필요한 객체 로딩 비용이 커졌습니다.  
이를 `JDBC + keyset(id > ?)` 기반으로 바꾸고, 이후 `bulkIndex(IndexQuery)`와 `refresh_interval = -1`까지 적용했습니다.

| 단계 | fetch(ms) | index(ms) | total(ms) |
| --- | ---: | ---: | ---: |
| 초기 샘플 | `6854.33` | `571.67` | `7455.67` |
| keyset / JDBC 전환 후 | `81.00` | `1493.00` | `1663.00` |
| `bulkIndex` 최적화 후 | `44.00` | `806.33` | `953.67` |

keyset/JDBC 전환 직후 `index(ms)`가 더 크게 보인 것은 fetch 지배 구간이 사라지면서, 원래 가려져 있던 bulk index 비용이 더 또렷하게 드러났기 때문입니다.

**이 단계의 결론:** ES 단계의 핵심 병목은 검색 엔진 자체보다 `source fetch`였고, 그 병목을 제거한 뒤에야 bulk index가 다음 최적화 대상으로 분명하게 보였습니다.

---

## 5. 최종 운영 비용

마지막에는 샘플 추정이 아니라 full run 실측으로 전체 비용을 봤습니다.

| 항목 | 값 |
| --- | ---: |
| 500만 건 `full rebuild` baseline | `11.7859분` |
| publish end-to-end | `13.0317분` |

두 수치는 같은 뜻이 아닙니다.

- `11.7859분`은 projection 전체 생성, ES full rebuild, synthetic cache eviction까지 포함한 `full rebuild` 기준선입니다.
- `13.0317분`은 실제 `chartUpdateJob`이 후보 버전 준비, 생성, 검증, 공개 전환까지 끝낸 publish end-to-end 시간입니다.

즉, 재생성 비용과 공개 비용은 같은 숫자로 말하면 안 됩니다.  
여기서 본 것은 생성 비용이고, 공개 일관성 자체는 `chart-pipeline.md`가 맡습니다.

**이 단계의 결론:** 차트 배치 비용은 `full rebuild`와 publish end-to-end를 분리해서 봐야 했고, 최종적으로는 둘 다 운영 가능한 분 단위까지 내려왔습니다.

---

## 6. 관련 문서

- [평점 집계 계층을 분리하고 결과적 일관성으로 수렴시키기](./rating-aggregation.md)
- [차트 공개 정합성을 위해 공개 파이프라인 다시 세우기](./chart-pipeline.md)
- [차트 API 조회 경로를 캐시·검색·폴백·메타데이터로 분리해 응답 병목 줄이기](./chart-serving.md)

---

## 7. 한 줄 요약

차트 재생성 경로를 writer, 쓰기 방식, ES `source fetch` 단계로 나눠 병목을 분해하고, JDBC 집계 쿼리와 `stage` 기반 쓰기 전략으로 500만 건 재생성 비용을 운영 가능한 수준까지 낮췄습니다.
