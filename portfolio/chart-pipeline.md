# 차트 공개 기준을 한 곳으로 모으고 공개 전환을 분리한 과정

차트는 배치만 끝나면 바로 공개가 끝나는 구조가 아니었습니다. MySQL 공개 조회 테이블, Elasticsearch `alias`, Redis 응답 캐시, `lastUpdated`, API 응답이 따로 움직이면 저장소마다 다른 차트가 보이거나 갱신 시각과 실제 결과가 어긋날 수 있었습니다.

문제는 속도보다 기준이었습니다. 지금 어떤 차트가 공개된 상태인지 한 곳에서 설명할 수 없었고, 장애가 나도 무엇으로 되돌려야 하는지 분명하지 않았습니다.

공개 기준은 `chart_publish_state.current_version`으로 모으고, 후보 생성과 공개 전환은 분리했습니다. 공개가 끝난 뒤에는 MySQL, Elasticsearch, Redis, `lastUpdated`, API 응답이 모두 같은 공개 버전과 같은 `logical_as_of_at`를 따라가도록 바꿨습니다.

---

## 1. 문제 상황

차트는 한 테이블만 읽는 기능이 아닙니다.  
배치 결과는 MySQL 공개 조회 테이블, Elasticsearch 인덱스, Redis 캐시, 공통 메타데이터인 갱신 시각, API 응답으로 흩어져 노출됩니다.

`배치가 끝났다`와 `사용자에게 새 차트가 공개됐다`를 같은 사건으로 볼 수 없었습니다.

당시에는 아래 같은 불일치가 가능했습니다.

- MySQL은 새 결과를 가리키는데 Elasticsearch `alias`는 이전 결과를 가리킬 수 있었습니다.
- Elasticsearch는 새 결과를 가리키는데 Redis 응답 캐시는 이전 버전 응답을 들고 있을 수 있었습니다.
- 응답 캐시는 비워졌는데 `lastUpdated`만 먼저 움직이면, 갱신됐다는 안내와 이전 버전 결과가 같이 보일 수 있었습니다.
- 배치가 중간에 실패하면 어떤 저장소는 새 결과를, 어떤 저장소는 이전 결과를 보여주는 상태가 생길 수 있었습니다.

처음에는 이걸 배치 속도 문제로 봤습니다. 배치를 더 빨리 돌리고 캐시만 잘 비우면 끝날 거라고 생각했습니다.

하지만 배치 완료 로그만으로는 사용자가 어떤 공개 버전을 보고 있는지 설명할 수 없었습니다. 더 큰 문제는 새 결과를 만들었다는 사실보다, 무엇을 공개 기준으로 볼 것인지 정해져 있지 않다는 점이었습니다.

차트는 사용자에게 노출되는 공개 지표입니다. 저장소마다 다른 결과를 보이면 신뢰도가 바로 흔들립니다. `lastUpdated`까지 공개 계약에 포함되지 않으면, 사용자에게는 갱신됐다고 보이는데 실제 결과는 예전 상태인 잘못된 공개가 됩니다.

질문도 바뀌었습니다.  
`배치를 얼마나 빨리 돌렸는가`가 아니라, `지금 공개된 차트를 어디에서 정의하고 어떻게 끝까지 같은 버전으로 보장할 것인가`가 핵심이 됐습니다.

---

## 2. 설계 방향

이 작업에서는 즉시성보다 공개 정합성과 롤백 가능성을 우선했습니다.

- 공개 기준은 `chart_publish_state.current_version` 한 곳에서 결정합니다.
- 후보 생성과 공개 전환은 같은 단계로 다루지 않습니다.
- MySQL, Elasticsearch, Redis, `lastUpdated`가 같은 공개 버전을 따라가게 합니다.
- Redis 키 유실이나 공개 실패가 생겨도 같은 기준으로 되돌릴 수 있게 합니다.

### 2-1. 구성

| 계층 | 책임 |
| --- | --- |
| 공개 상태 계층 (`chart_publish_state`, `chart_publish_history`) | 현재/이전/후보 버전과 공개 상태, 롤백 기준 관리 |
| 후보 생성 계층 (`chart_scores_stage`, 후보 Elasticsearch 인덱스) | 공개 전 검증 대상 보관 |
| 공개 조회 계층 (`chart_scores`, Elasticsearch 공개 `alias`, Redis 응답 캐시) | 현재 공개 버전 기준 사용자 조회 제공 |
| 공개 메타데이터 (`logical_as_of_at`, `lastUpdated`) | 현재 공개 버전의 논리 시점과 갱신 시각 설명 |

중심은 단순합니다. 공개 기준은 `chart_publish_state.current_version`에 두고, 다른 저장소는 그 기준을 따라가게 했습니다.

---

## 3. 구현 과정

### 3-1. 후보를 먼저 만들고, 검증 뒤에만 공개했습니다

새 차트를 계산하는 일과 사용자가 보는 차트를 바꾸는 일을 같은 사건으로 다루지 않았습니다. 둘이 묶여 있으면 배치 중간에도 저장소마다 다른 버전이 섞일 수 있기 때문입니다.

차트 배치는 입력 데이터를 읽어 먼저 후보를 만들고, 검증을 통과한 뒤에만 공개 기준을 바꾸도록 `generate -> validate -> publish -> serve` 순서로 나눴습니다.

```text
chart input data
  -> 후보 버전 생성
  -> chart_scores_stage 적재
  -> 후보 Elasticsearch 인덱스 생성
  -> 검증
  -> 공개
  -> Redis 공개 버전 / 갱신 시각 / 응답 캐시 반영
  -> 현재 공개 버전만 서빙
```

#### 후보 생성(`generate`)

후보 생성 단계에서는 공개 기준을 건드리지 않고 새 결과만 따로 만듭니다.

- 후보 버전을 만듭니다.
- 후보 테이블 `chart_scores_stage`를 준비합니다.
- 후보 Elasticsearch 인덱스 이름을 확정합니다.
- `chart_publish_state`에 `candidate_version`과 후보 참조를 기록합니다.

이 단계에서는 `current_version`을 바꾸지 않습니다. 배치가 새 차트를 만들고 있어도 공개 경로는 계속 이전 공개 버전을 바라보게 해서, 계산 중 결과가 사용자 쪽으로 섞이지 않게 했습니다.

#### 검증(`validate`)

후보가 만들어졌다고 바로 공개하지는 않습니다. 공개 최소 조건을 먼저 확인합니다.

- MySQL 후보 행 수가 0보다 커야 합니다.
- MySQL 후보 행 수와 Elasticsearch 후보 문서 수가 맞아야 합니다.
- 후보 Elasticsearch 인덱스가 실제 검색 가능한 상태여야 합니다.

여기서 하나라도 어긋나면 `current_version`은 그대로 둡니다. 새 결과가 조금 늦게 나오는 것보다, 덜 만들어진 결과가 공개 경로에 섞이는 쪽이 더 위험했기 때문입니다.

#### 공개(`publish`)

실제 공개 상태는 이 단계에서만 바뀝니다.

- MySQL 공개 조회 테이블을 교체합니다.
- Elasticsearch `alias`를 후보 인덱스로 전환합니다.
- `chart_publish_state`를 `PUBLISHED`로 갱신합니다.
- Redis에 공개 버전을 반영합니다.
- 차트 응답 캐시를 비웁니다.
- `lastUpdated`를 `logical_as_of_at` 기준으로 반영합니다.

여러 저장소를 각각 따로 바꾸지 않고, 하나의 공개 절차로 묶었습니다. 그래야 MySQL은 새 결과를 가리키는데 Redis나 Elasticsearch는 예전 버전을 가리키는 상태를 줄일 수 있습니다.

또 publish 메서드가 끝나는 시각과 실제 API에 새 버전이 보이는 시각은 완전히 같지 않았습니다. `publishedAt`과 `apiVisibleAt`을 따로 측정한 이유도 여기에 있습니다.

#### 서빙(`serve`)

서빙 단계는 항상 공개 버전만 따라갑니다.

- 응답 캐시 키는 `chart:v1:{publishedVersion}:...` 형태의 버전별 이름공간을 씁니다.
- `ChartPublishedVersionService`는 Redis의 공개 버전을 우선 읽고, 키가 비거나 읽기에 실패하면 `chart_publish_state.current_version`으로 되돌아갑니다.
- `ChartLastUpdatedService`는 공개 모드에서 `chart_publish_state.logical_as_of_at`를 기준으로 `lastUpdated`를 만듭니다.

후보가 먼저 만들어져 있어도 공개 전환이 끝나기 전까지는 사용자가 **현재 공개 버전 기준 응답**만 보게 하는 구조입니다. 공개 뒤에도 버전 키와 `logical_as_of_at`를 함께 따라가게 해서 결과와 갱신 시각이 서로 다른 뜻을 갖지 않도록 했습니다.

### 3-2. 저장소마다 공개 경계를 따로 뒀습니다

#### MySQL

MySQL에서는 계산 중 결과와 공개 중 결과를 섞지 않기 위해 후보 테이블 `chart_scores_stage`와 공개 조회 테이블을 분리했습니다.

1. `chart_scores_stage`에 후보 결과를 적재합니다.
2. 검증이 끝난 뒤에만 `RENAME TABLE`로 공개 조회 테이블을 교체합니다.
3. 이전 공개 테이블은 `chart_scores_prev`로 보관합니다.

계산 도중에는 아무리 데이터가 쌓여도 공개 테이블은 그대로이고, 공개 단계에 들어갔을 때만 한 번에 전환됩니다.

#### Elasticsearch

Elasticsearch도 같은 방식으로 정리했습니다.

- 후보 인덱스 이름은 `chart.search.index-name + "_" + normalize(version)` 규칙으로 생성합니다.
- 배치는 공개 `alias`가 아니라 후보 인덱스를 다시 만듭니다.
- 검증이 끝나기 전까지 공개 `alias`는 그대로 둡니다.
- 공개 시점에만 `alias`를 후보 인덱스로 전환합니다.

재색인 도중 문서가 일부만 들어간 상태가 바로 사용자 검색 결과로 노출되지 않게 하려면, 계산 중인 인덱스와 현재 공개 인덱스를 분리해야 했습니다.

#### Redis와 `lastUpdated`

공개 불일치는 저장소 데이터만의 문제가 아니었습니다. Redis 캐시와 `lastUpdated`가 먼저 움직여도 사용자 입장에서는 잘못된 공개입니다.

공개 모드에서는 의미를 다시 정리했습니다.

- 응답 캐시는 공개 버전별 이름공간으로 분리합니다.
- Redis 키가 유실되거나 읽기에 실패해도 `chart_publish_state.current_version`으로 되돌아갈 수 있게 했습니다.
- `lastUpdated`는 단순 시계 시간이 아니라, 현재 공개 버전의 논리 시점인 `logical_as_of_at`를 뜻하게 했습니다.

이제 `lastUpdated`는 `배치가 언제 끝났는가`가 아니라, `지금 공개된 차트가 어느 시점의 데이터를 반영하는가`를 설명하는 값이 됩니다.

---

## 4. 공개 기준 검증

여기서 확인한 것은 publish 메서드가 끝났는지 여부가 아니었습니다.

- 실제 API는 언제 새 버전을 내보내는가
- Redis, Elasticsearch, API가 정말 같은 공개 버전을 가리키는가
- 장애가 나도 그 기준을 다시 복원할 수 있는가

직접 확인한 값은 아래와 같습니다.

| 항목 | 값 |
| --- | --- |
| `publishedAt` | `2026-03-15T03:37:41.427491` |
| `apiVisibleAt` | `2026-03-15T12:37:41.7235172+09:00` |
| `publishToApiVisibleMillis` | `296.03ms` |
| `current_version` | `v20260315032444512` |
| `logical_as_of_at` | `2026-03-14T08:30:37` |

공개 뒤 상태도 같은 기준으로 맞았습니다.

- Redis published version: `v20260315032444512`
- Elasticsearch `alias`: `chart_scores_bench_published -> chart_scores_bench_v20260315032444512`
- API 응답: `version=v20260315032444512`, `lastUpdated=2026-03-14T08:30:37`

이 검증에서 확인한 것은 세 가지입니다.

- publish 메서드가 끝났다고 API가 같은 순간에 새 버전을 내보내는 것은 아니었습니다. `publishedAt`과 `apiVisibleAt` 사이에는 `296.03ms`가 있었습니다.
- 공개가 안정화된 뒤에는 Redis metadata, Elasticsearch `alias`, DB publish state, API 응답이 모두 같은 `version`과 같은 `logical_as_of_at`를 가리켰습니다.
- 롤백과 Redis 키 유실 상황에서도 공개 버전과 `lastUpdated`를 다시 복원할 수 있었습니다.

즉, 이 작업은 배치를 돌리는 구조를 정리한 데서 끝나지 않았습니다. 새 버전을 실제 사용자에게 일관되게 공개할 수 있는지까지 확인한 작업이었습니다.

---

## 5. 관련 문서

- [차트 재생성 배치 비용 줄이기](./chart-batch-performance.md)
- [차트 API 조회 경로를 캐시·검색·폴백·메타데이터로 분리해 응답 병목 줄이기](./chart-serving.md)

---
