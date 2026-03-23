# 차트 공개 일관성을 위해 publish 파이프라인 다시 세우기

> 차트 시스템에서 중요한 것은 배치가 새 결과를 계산했다는 사실 자체가 아니라, 사용자가 MySQL, Elasticsearch, Redis, 메타데이터, API 어디를 보더라도 같은 공개 버전을 보게 만드는 것입니다.  
> 이 문서는 `generate -> validate -> publish -> serve`를 분리하고, `chart_publish_state`를 공개 기준점으로 세워 공개 버전 식별자, `logical_as_of_at`, 부분 공개 불일치 방지, 롤백 경계를 만든 과정을 정리합니다.

---

## 1. 문제 정의

차트는 한 테이블만 읽는 기능이 아니었습니다.  
배치가 만든 결과가 MySQL 조회 테이블, Elasticsearch 인덱스, Redis 캐시, 메타데이터, API 응답으로 흩어져 노출되는 구조였기 때문에, 계산 완료와 공개 완료를 같은 사건으로 취급할 수 없었습니다.

실제로 위험했던 것은 배치 속도 자체보다 공개 기준의 부재였습니다.

- MySQL은 새 결과를 가리키는데 Elasticsearch alias는 이전 결과를 가리킬 수 있었습니다.
- Elasticsearch는 새 결과를 가리키는데 Redis 응답 캐시는 이전 버전 응답을 들고 있을 수 있었습니다.
- 응답 캐시는 비워졌는데 `lastUpdated`만 먼저 움직이면, 사용자는 업데이트된 차트라는 메타데이터와 이전 버전 결과를 동시에 볼 수 있었습니다.
- 배치가 중간에 실패하면 어떤 저장소는 새 결과를, 어떤 저장소는 이전 결과를 보여주는 부분 공개 불일치가 생길 수 있었습니다.

여기서 질문을 다시 잡았습니다.  
차트 시스템의 핵심은 "배치를 얼마나 빨리 돌렸는가"가 아니라, **지금 공개된 차트를 어디에서 정의하고 어떻게 끝까지 같은 버전으로 보장할 것인가**였습니다.

---

## 2. 공개 기준: `version`과 `chart_publish_state`

이 문서에서 `version`은 도메인 원본 데이터의 필드가 아닙니다.  
차트 API 응답 상단에서 현재 공개된 스냅샷을 식별하는 **공개 버전 식별자**입니다.

예를 들면 API는 아래처럼 현재 공개 버전을 함께 반환합니다.

```json
{
  "version": "v20260314153000000",
  "lastUpdated": "2026-03-14T15:30:00",
  "items": []
}
```

즉, `version`은 `ratings`나 `release_rating_summary` 안의 원본 값이 아니라, 사용자가 지금 보고 있는 차트가 어느 공개 스냅샷인지 설명하는 값입니다.

이 기준점을 코드에서 맡는 테이블이 `chart_publish_state`입니다.  
`chart_publish_state`는 아래 상태를 함께 관리합니다.

- `current_version`, `previous_version`, `candidate_version`
- `status`
- `mysql_projection_ref`, `previous_mysql_projection_ref`, `candidate_mysql_projection_ref`
- `es_index_ref`, `previous_es_index_ref`, `candidate_es_index_ref`
- `logical_as_of_at`, `previous_logical_as_of_at`, `candidate_logical_as_of_at`
- `published_at`
- `last_validation_status`
- `last_error_code`, `last_error_message`

추가로 `chart_publish_history`를 두어 아래 이력을 남깁니다.

- `GENERATING`
- `VALIDATING`
- `PUBLISHED`
- `FAILED`
- `ROLLED_BACK`

핵심은 Redis 키나 Elasticsearch alias 자체가 공개 기준점이 아니라는 점입니다.  
실제로 "지금 공개된 차트"를 결정하는 값은 `chart_publish_state.current_version`이고, 다른 저장소들은 그 기준을 따라가야 합니다.

---

## 3. `generate -> validate -> publish -> serve`

공개 흐름은 아래처럼 분리했습니다.

```text
release_rating_summary
  -> generate candidate version
  -> chart_scores_stage
  -> candidate Elasticsearch index
  -> validate
  -> publish
  -> Redis published version / lastUpdated / response cache
  -> serve only current published version
```

### 3-1. generate

generate 단계에서는 아직 공개 상태를 건드리지 않습니다.

- 후보 버전을 만듭니다.
- `chart_scores_stage`를 준비합니다.
- 후보 Elasticsearch 인덱스 이름을 확정합니다.
- `chart_publish_state`에 `candidate_version`과 후보 참조를 기록합니다.

중요한 점은 이 단계가 "새 차트를 만들기 시작했다"는 뜻이지, "새 차트를 공개했다"는 뜻은 아니라는 점입니다.

### 3-2. validate

validate 단계에서는 후보 스냅샷이 공개 가능한 상태인지 확인합니다.

- MySQL 후보 행 수가 0보다 커야 합니다.
- MySQL 후보 행 수와 Elasticsearch 후보 문서 수가 맞아야 합니다.
- 후보 Elasticsearch 인덱스가 실제 검색 가능한 상태여야 합니다.

이 단계가 실패하면 `current_version`은 바뀌지 않습니다.  
즉, 검증 실패는 공개 실패일 뿐이고, 기존 공개 버전은 그대로 유지됩니다.

### 3-3. publish

publish 단계에서만 실제 공개 상태를 전환합니다.

- MySQL 공개 테이블을 교체합니다.
- Elasticsearch alias를 후보 인덱스로 전환합니다.
- `chart_publish_state.markPublished(version)`를 호출합니다.
- Redis에 공개 버전을 반영합니다.
- 차트 응답 캐시를 비웁니다.
- `lastUpdated`를 `logical_as_of_at` 기준으로 맞춥니다.

즉, publish는 단순 저장이 아니라, 여러 저장소의 공개 상태를 한 계약으로 전환하는 단계입니다.

### 3-4. serve

serve 단계에서는 후보가 아니라 **현재 공개 버전만** 읽습니다.

- 응답 캐시 키는 `chart:v1:{publishedVersion}:...` 형태의 버전별 이름공간을 사용합니다.
- `ChartPublishedVersionService`는 Redis에 저장된 공개 버전을 우선 읽고, 실패하면 `chart_publish_state.current_version`으로 되돌아갑니다.
- `ChartLastUpdatedService`는 공개 모드에서 `chart_publish_state.logical_as_of_at`를 기준으로 `lastUpdated`를 노출합니다.

이 구조에서는 새 후보 데이터를 먼저 만들어도, publish 전까지는 API가 그 결과를 보여주지 않습니다.

---

## 4. 부분 공개 불일치 방지

### 4-1. MySQL은 후보와 공개를 분리했습니다

MySQL에서 계산 중 결과와 공개 중 결과를 섞지 않기 위해 `chart_scores_stage`와 공개 테이블을 분리했습니다.

1. `chart_scores_stage`에 후보 결과를 적재합니다.
2. 검증이 끝난 뒤에만 `RENAME TABLE`로 공개 테이블을 교체합니다.
3. 이전 공개 테이블은 `chart_scores_prev`로 보관합니다.

실제 공개의 중심 동작은 `ChartScoreQueryRepository.publishStageTable()`의 아래 절차입니다.

- `DROP TABLE IF EXISTS chart_scores_prev`
- `RENAME TABLE chart_scores TO chart_scores_prev, chart_scores_stage TO chart_scores`
- 새 `chart_scores_stage`를 다시 생성

즉, 배치가 후보를 만들고 있다는 사실과 MySQL에서 공개 테이블이 바뀌는 시점은 분리됩니다.

### 4-2. Elasticsearch도 후보 인덱스와 공개 alias를 분리했습니다

Elasticsearch도 같은 기준 아래로 옮겼습니다.

- 후보 인덱스 이름은 `chart.search.index-name + "_" + normalize(version)` 규칙으로 생성합니다.
- 배치는 공개 인덱스가 아니라 후보 인덱스를 다시 만듭니다.
- 검증이 끝나기 전까지 공개 alias는 그대로 둡니다.
- publish 시점에만 alias를 후보 인덱스로 전환합니다.

이 덕분에 검색 계층에서도 계산 중인 인덱스와 현재 공개 인덱스를 분리할 수 있게 됐습니다.

### 4-3. Redis와 메타데이터도 공개 버전 기준으로 다시 정의했습니다

공개 불일치는 저장소 데이터만의 문제가 아니었습니다.  
Redis 캐시와 메타데이터가 먼저 움직여도 사용자 입장에서는 잘못된 공개입니다.

그래서 공개 모드에서는 의미를 아래처럼 다시 정의했습니다.

- 응답 캐시는 공개 버전별 이름공간으로 분리합니다.
- Redis가 일시적으로 비어도 `chart_publish_state.current_version`으로 되돌아갈 수 있게 했습니다.
- `lastUpdated`는 단순 시계 시간이 아니라, 현재 공개된 스냅샷의 논리 시점인 `logical_as_of_at`를 의미하게 했습니다.

즉, `lastUpdated`는 "배치가 언제 끝났는가"가 아니라, "지금 공개된 차트가 어느 시점의 데이터를 반영하는가"를 설명하는 값입니다.

---

## 5. 검증 실패와 롤백

이 파이프라인에서 중요한 규칙은 하나입니다.  
**검증이 끝나기 전에는 `current_version`이 절대 바뀌지 않는다**는 점입니다.

실제 구현에서도 검증이 실패하면 아래 전환은 일어나지 않습니다.

- MySQL 공개 테이블 교체
- Elasticsearch alias 전환
- Redis 공개 버전 갱신
- `lastUpdated` 갱신

롤백도 alias만 되돌리는 수준이 아닙니다.  
`previous_version`을 기준으로 아래 상태를 함께 복구합니다.

- MySQL 공개 테이블
- Elasticsearch alias
- `chart_publish_state`
- Redis 공개 버전
- 차트 응답 캐시
- `lastUpdated`

즉, 롤백의 목적은 실패한 후보를 지우는 것이 아니라, 사용자가 다시 **하나의 이전 공개 버전**을 보게 만드는 것입니다.

---

## 6. 결과

이 문서에서 남겨야 할 수치는 공개 일관성 주장에 직접 연결되는 값만 남겼습니다.

| 항목 | 측정값 | 의미 |
| --- | --- | --- |
| 공개 후 반영 지연 | **296.03ms** | `publishedAt` 이후 API가 새 공개 버전을 반환하기까지의 지연입니다. |
| 공개 일관성 검증 | **DB state / Redis / ES alias / API 동일 version** | 여러 저장소가 실제로 같은 공개 버전을 가리키는지 확인한 결과입니다. |
| 메타데이터 기준 | **`lastUpdated = logical_as_of_at`** | 시계 시간이 아니라 공개 스냅샷의 논리 시점을 보여주게 했습니다. |

이 수치가 의미하는 바는 세 가지입니다.

- publish 시점과 API 가시 시점은 분리해서 봐야 합니다.
- 공개 성공은 배치 완료가 아니라, 여러 저장소가 같은 공개 버전을 가리키는 상태를 뜻합니다.
- `lastUpdated`도 공개 계약의 일부로 다뤄야 합니다.

---

## 7. 아직 남은 일

- 검증은 아직 최소 차단 조건 수준입니다.
- 운영 절차 문서와 자동 간단 검증까지 완성한 것은 아닙니다.
- `chart.publish.enabled`로 기존 경로와 공개 경로를 공존시켰기 때문에, 실행 모드 해석은 설정과 함께 봐야 합니다.

이번 단계의 목표는 완성형 플랫폼이 아니었습니다.  
여러 저장소에 걸친 차트 공개를 부분 공개 불일치 없이 통제할 수 있는 최소 공개 아키텍처를 코드로 닫는 것이 목표였습니다.

---

## 8. 관련 코드

아래 코드는 이 공개 파이프라인을 실제로 구현한 핵심 파일들입니다.

- `src/main/java/com/hipster/chart/publish/domain/ChartPublishState.java`
- `src/main/java/com/hipster/chart/publish/domain/ChartPublishHistory.java`
- `src/main/java/com/hipster/chart/publish/service/ChartPublishStateService.java`
- `src/main/java/com/hipster/chart/publish/service/ChartPublishOrchestratorService.java`
- `src/main/java/com/hipster/chart/config/ChartPublishProperties.java`
- `src/main/java/com/hipster/batch/chart/config/ChartJobConfig.java`
- `src/main/java/com/hipster/batch/chart/repository/ChartScoreQueryRepository.java`
- `src/main/java/com/hipster/chart/service/ChartElasticsearchIndexService.java`
- `src/main/java/com/hipster/chart/service/ChartLastUpdatedService.java`
- `src/main/java/com/hipster/chart/publish/service/ChartPublishedVersionService.java`
- `src/test/java/com/hipster/chart/publish/service/ChartPublishJobIntegrationTest.java`
- `src/test/java/com/hipster/chart/publish/service/ChartPublishEndToEndIntegrationTest.java`
- `src/test/java/com/hipster/chart/publish/service/ChartPublishAliasRollbackIntegrationTest.java`
- `src/test/java/com/hipster/chart/publish/service/ChartPublishFailurePathIntegrationTest.java`
- `src/test/java/com/hipster/chart/publish/service/ChartPublishRedisIntegrationTest.java`

---

## 9. 관련 문서

아래 문서는 이 공개 파이프라인과 맞닿은 관련 문서입니다.

- [평점 조회와 차트 배치가 함께 쓰는 공통 집계 계층 만들기](./rating-aggregation.md)
- [차트 API 읽기 경로를 캐시·검색·fallback·메타데이터로 분리해 응답 병목 줄이기](./chart-serving.md)

---

## 10. 한 줄 요약

`chart_publish_state`를 공개 기준점으로 세우고 `generate -> validate -> publish -> serve`를 분리해, MySQL 조회 테이블, Elasticsearch, Redis, 메타데이터, API가 모두 같은 공개 버전을 보도록 차트 publish 파이프라인을 다시 설계했습니다.
