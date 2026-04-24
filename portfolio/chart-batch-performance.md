# 차트 재생성 배치 비용 줄이기

차트 조회를 빨리 만들어도, 차트를 다시 만드는 배치가 오래 걸리면 운영이 버겁습니다. `chart_scores` 재생성 비용을 줄인 과정입니다.

- writer 병목
- 쓰기 전략 전환
- ES 재색인 병목
- 500만 건 `full rebuild` 비용

---

## 1. 먼저 느렸던 곳: writer 안의 `metadata fetch + upsert`

초기 projection 샘플 실행에서는 시간이 writer에 몰렸습니다.

| 항목 | 값 |
| --- | ---: |
| 평균 청크 시간 | 약 `970ms` |
| 총 청크 수 | `2500` |
| 500만 건 projection 총 시간 추정 | 약 `40.4분` |

writer를 `metadata fetch`, `serialization`, `db write`로 나눠 보니, 비싼 구간은 `metadata fetch + upsert`였습니다.

- JPA fetch join으로 메타데이터를 읽고
- Java에서 JSON을 조립한 뒤
- 본 테이블에 `UPSERT`하는 흐름이었습니다.

이 배치에서는 이 흐름이 너무 무거웠습니다. 차트 쓰기에 필요한 모양은 이미 정해져 있었고, Java 쪽에서 다시 조립할 이유가 크지 않았습니다. 메타데이터를 JDBC 집계 쿼리로 바로 만들고, SQL이 차트 쓰기용 형태를 바로 내놓게 바꿨습니다.

| 항목 | Before | After | 개선율 |
| --- | ---: | ---: | ---: |
| `metadata fetch` | `935.67ms` | `128.33ms` | `-86.3%` |
| `serialization` | `52.67ms` | `0.67ms` | `-98.7%` |
| `upsert` | `1080.33ms` | `406.00ms` | `-62.4%` |
| `total` | `2109.67ms` | `573.67ms` | `-72.8%` |

500만 건 기준 단순 환산도 `약 87.9분 -> 약 23.9분`까지 내려갔습니다.

- writer 병목은 막연한 DB write가 아니었습니다.
- 메타데이터를 읽고 조립하는 비용이 컸습니다.
- 그 비용을 줄이자 `upsert` 구간도 같이 내려갔습니다.

그래도 남은 문제가 있었습니다. `406ms / 2000건` 수준의 DB write는 여전히 비쌌습니다. 메타데이터를 줄여도, 본 테이블 `UPSERT` 자체가 비싸면 배치 전체는 다시 막힙니다.

---

## 2. 본 테이블 `UPSERT`보다 stage insert가 더 잘 맞았습니다

다음에는 `chart_scores`를 본 테이블에 바로 `UPSERT`하는 방식이 맞는지 봤습니다.

비교한 방식은 세 가지였습니다.

- `UPSERT`
  - `chart_scores` 본 테이블에 직접 `ON DUPLICATE KEY UPDATE`
- `STAGING_INSERT`
  - `chart_scores_stage_bench`에 insert-only 적재
- `LIGHT_STAGE_INSERT`
  - 인덱스와 제약조건을 더 줄인 `chart_scores_stage_light_bench`에 insert-only 적재

| mode | avg db write(ms) | avg total(ms) |
| --- | ---: | ---: |
| `UPSERT` | `592.67` | `836.67` |
| `STAGING_INSERT` | `387.00` | `557.33` |
| `LIGHT_STAGE_INSERT` | `298.67` | `434.33` |

본 테이블 `UPSERT`보다 stage insert가 더 쌌고, 그중에서도 `LIGHT_STAGE_INSERT`가 가장 가벼웠습니다.

이 배치에서는 stage가 더 유리했습니다.

- 본 테이블 `UPSERT`는 기존 행 확인과 인덱스 갱신 비용을 계속 안고 갑니다.
- stage는 비어 있는 테이블에 넣기만 하면 됩니다.
- 읽기 트래픽이 보는 테이블과도 직접 부딪히지 않습니다.

차트는 요청마다 조금씩 고치는 데이터보다, 새 snapshot을 만들어 나중에 공개하는 데이터에 가깝습니다. 이런 배치에서는 live table을 계속 갱신하는 것보다, 비어 있는 stage에 한 번 밀어 넣는 쪽이 더 잘 맞습니다.

`LIGHT_STAGE_INSERT`는 여기서 한 걸음 더 갔습니다. stage 테이블 인덱스와 제약조건을 최소화해 쓰기 자체를 더 가볍게 만들었습니다.

대신 따라오는 비용도 있습니다.

- 차트 볼륨만큼 stage 공간이 더 필요합니다.
- 본 테이블과 stage 테이블을 같이 관리해야 합니다.
- 나중에 공개 전환 단계가 하나 더 필요합니다.

재생성 비용만 놓고 보면 `UPSERT`보다 stage insert가 훨씬 낫습니다.

---

## 3. ES 재색인에서 느린 쪽은 write보다 `source fetch`였습니다

writer와 쓰기 전략을 정리한 뒤에는 ES 재색인이 다음 병목으로 남았습니다.

처음에는 ES write가 느린 줄 알았습니다. 실제로는 그 전에 source를 가져오는 구간이 더 비쌌습니다.

기존 경로는 `PageRequest` 기반 페이지 접근과 엔티티 fetch에 가까웠습니다. 깊은 페이지로 갈수록 읽기 비용이 커졌고, 배치에 필요 없는 객체 로딩도 같이 따라왔습니다.

이 경로를 `JDBC + keyset(id > ?)` 방식으로 바꾸고, 그 뒤에 `bulkIndex(IndexQuery)`와 `refresh_interval = -1`을 적용했습니다.

| 단계 | fetch(ms) | index(ms) | total(ms) |
| --- | ---: | ---: | ---: |
| 초기 샘플 | `6854.33` | `571.67` | `7455.67` |
| keyset / JDBC 전환 후 | `81.00` | `1493.00` | `1663.00` |
| `bulkIndex` 최적화 후 | `44.00` | `806.33` | `953.67` |

- 처음에는 `source fetch`가 너무 커서 ES write 비용이 가려져 있었습니다.
- fetch를 줄이자 그다음 병목이 `bulk index`로 드러났습니다.
- 그 뒤에야 `bulkIndex`와 refresh 설정을 손보는 일이 의미가 생겼습니다.

이 배치에서 중요한 선택은 `랜덤 I/O냐 순차 I/O냐` 같은 일반 설명이 아닙니다. 필요한 것은 500만 건 source를 꾸준히 읽어 ES로 넘기는 일이고, 그 작업에는 깊은 페이지 접근보다 keyset + JDBC가 훨씬 잘 맞았습니다.

남은 것은 ES write 비용이었습니다. fetch를 정리하고 나서야 `bulkIndex`가 실제 최적화 대상으로 보였고, refresh 전략까지 함께 조정하면서 재색인 전체 시간을 더 낮출 수 있었습니다.

---

## 4. 최종 비용은 `full rebuild` 기준으로 봐야 합니다

마지막에는 샘플 추정 대신 full run 실측을 봤습니다.

| 항목 | 값 |
| --- | ---: |
| 500만 건 `full rebuild` baseline | `11.7859분` |
| publish end-to-end | `13.0317분` |

두 값은 같은 뜻이 아닙니다.

- `11.7859분`은 projection 생성, ES full rebuild, synthetic cache eviction까지 포함한 `full rebuild` 비용입니다.
- `13.0317분`은 후보 준비, 검증, 공개 전환까지 포함한 end-to-end 시간입니다.

여기서 봐야 할 숫자는 `11.7859분`입니다. 차트 결과를 다시 만드는 배치 비용이 500만 건 기준으로 어디까지 내려왔는지 보여 줍니다.

`13.0317분`은 따로 봐야 합니다. publish는 full rebuild와 같은 숫자로 보면 안 됩니다.

- writer에서는 `metadata fetch + upsert`가 비쌌고
- 쓰기 전략은 본 테이블 `UPSERT`보다 stage insert가 잘 맞았고
- ES 재색인에서는 write보다 `source fetch`를 먼저 줄여야 했고
- 그 결과 500만 건 `full rebuild`를 `11.7859분` 수준까지 내렸습니다.

---

## 관련 문서

- [차트 공개 정합성을 위해 공개 파이프라인 다시 세우기](./chart-pipeline.md)
- [차트 API 조회 경로를 캐시·검색·폴백·메타데이터로 분리해 응답 병목 줄이기](./chart-serving.md)
