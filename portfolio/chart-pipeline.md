# 차트 배치 재생성과 공개를 분리해 안전한 publish 파이프라인 만들기

> 차트 결과를 계산했다는 사실만으로는 아직 아무것도 공개된 것이 아닙니다.  
> 이 문서는 `generate -> validate -> publish -> serve`를 분리하고, `chart_publish_state`를 authoritative publish pointer로 세워 MySQL projection, Elasticsearch, Redis, `lastUpdated` 메타데이터, API가 모두 같은 published version을 가리키게 만든 과정입니다.

이 문서에서 보여드리고 싶은 핵심은 Elasticsearch alias를 바꿨다는 사실이 아닙니다.  
핵심은 차트 계산 결과를 바로 덮어쓰지 않고, 여러 저장소에 퍼지는 차트 결과를 **같은 published version**으로 정의하고 공개하는 publish semantics를 만든 것입니다.

즉, 차트 문제를 "배치를 빨리 돌리는 일"이 아니라, **무엇이 지금 공개된 차트인가를 단일 기준으로 정의하는 일**로 다시 풀었습니다.

---

## 1. 문제 상황

차트는 MySQL projection 하나만 읽는 시스템이 아니었습니다.  
배치가 만든 결과는 MySQL `chart_scores`, Elasticsearch 검색 인덱스, Redis 응답 캐시, `version`과 `lastUpdated` 메타데이터를 거쳐 최종 API로 노출됩니다.

문제는 계산 자체보다 공개 기준의 부재였습니다.

- MySQL projection은 새 결과를 가리키는데 ES alias는 아직 이전 결과를 볼 수 있었습니다.
- ES는 새 결과를 가리키는데 Redis 응답 캐시는 이전 버전 응답을 들고 있을 수 있었습니다.
- 응답 캐시는 비워졌는데 `lastUpdated`만 먼저 움직이면, 사용자는 "업데이트된 차트"라는 메타데이터와 "이전 버전 결과"를 동시에 볼 수 있었습니다.
- 배치가 중간에 실패하면 어떤 저장소는 새 결과를, 어떤 저장소는 옛 결과를 보는 partial publish가 발생할 수 있었습니다.

즉, 차트 문제의 본질은 계산 시간이 아니라 **공개 의미론의 붕괴 위험**이었습니다.  
계산 완료와 공개 완료를 같은 사건으로 취급하면, 여러 저장소가 있는 환경에서 "지금 공개된 차트"를 한 문장으로 설명할 수 없게 됩니다.

---

## 2. 구조적 판단

이 시점에서 중요한 질문은 두 가지였습니다.

1. 차트 결과를 계산하는 순간 바로 공개된 것으로 봐도 되는가
2. Redis나 ES alias 같은 빠른 저장소가 공개 기준점 역할까지 맡아도 되는가

제가 내린 답은 둘 다 아니었습니다.

차트는 실시간 갱신 데이터가 아니라, 배치가 만든 **버전드 스냅샷을 공개하는 문제**로 다시 정의해야 했습니다.  
그리고 여러 저장소에 퍼진 결과를 하나의 차트로 취급하려면, 그 저장소들 바깥에서 "현재 공개 버전"을 단일하게 정의하는 authoritative publish pointer가 필요했습니다.

그 역할은 `chart_publish_state`가 맡습니다.

- `chart_publish_state`
  - 현재 공개 버전, 이전 버전, candidate 버전과 각 버전의 MySQL/ES 참조, `logical_as_of_at`, `published_at`를 함께 들고 있는 공개 기준점입니다.
- `chart_scores_stage`
  - 아직 공개되지 않은 candidate MySQL projection입니다.
- candidate ES index
  - 아직 alias가 가리키지 않는 candidate 검색 결과입니다.
- version-aware Redis cache/meta
  - 공개 기준점이 바뀐 뒤에만 움직이는 보조 계층입니다.

핵심은 Redis와 ES alias가 공개 기준점이 아니라는 점입니다.  
이들은 빠른 조회와 라우팅을 맡는 보조 수단일 뿐이고, 실제로 "무엇이 지금 공개된 차트인가"는 `chart_publish_state.current_version`이 결정합니다.

또한 candidate와 published를 물리적으로 분리해야만 partial publish를 통제할 수 있었습니다.  
같은 테이블, 같은 인덱스, 같은 캐시 namespace를 계속 덮어쓰는 구조에서는 계산 중간 상태와 공개 상태를 분리할 수 없기 때문입니다.

---

## 3. 해결 과정

### 3-1. 공개 기준점이 없어서 `chart_publish_state`를 먼저 도입했습니다

가장 먼저 해결한 것은 "현재 공개된 차트"를 어디에서 정의할 것인가였습니다.

현재 구현에서 `chart_publish_state`는 아래 정보를 함께 관리합니다.

- `current_version`, `previous_version`, `candidate_version`
- `status`
- `mysql_projection_ref`, `previous_mysql_projection_ref`, `candidate_mysql_projection_ref`
- `es_index_ref`, `previous_es_index_ref`, `candidate_es_index_ref`
- `logical_as_of_at`, `previous_logical_as_of_at`, `candidate_logical_as_of_at`
- `published_at`
- `last_validation_status`
- `last_error_code`, `last_error_message`

즉, 이 테이블은 단순 상태 로그가 아닙니다.  
현재 무엇이 공개되어 있고, 그 공개가 어떤 MySQL projection과 어떤 ES index, 어떤 논리 시점을 가리키는지까지 함께 잡는 authoritative publish pointer입니다.

추가로 `chart_publish_history`를 두어 아래 이력을 남기도록 했습니다.

- `GENERATING`
- `VALIDATING`
- `PUBLISHED`
- `FAILED`
- `ROLLED_BACK`

그리고 각 이력에는 `row_count_mysql`, `doc_count_es`, `validation_summary_json`, `source_snapshot_at`, `published_at`, `rolled_back_at`을 남겨, 운영자가 "무슨 버전을 왜 공개했고 왜 실패했고 왜 되돌렸는가"를 추적할 수 있게 했습니다.

### 3-2. MySQL projection도 candidate와 published를 분리했습니다

공개 기준점을 세웠다고 끝나지 않습니다.  
MySQL projection 자체가 계산 중 결과와 공개 중 결과를 분리하지 못하면, 여전히 partial publish 위험이 남습니다.

그래서 MySQL projection 경로를 아래처럼 바꿨습니다.

1. `chart_scores_stage`를 candidate projection 테이블로 준비합니다.
2. 배치가 candidate 결과를 `chart_scores_stage`에 bulk upsert 합니다.
3. 검증이 끝난 뒤에만 `RENAME TABLE`로 publish swap을 수행합니다.
4. 기존 공개 테이블은 `chart_scores_prev`로 보관합니다.

실제 publish 핵심은 `ChartScoreQueryRepository.publishStageTable()`의 아래 절차입니다.

- `DROP TABLE IF EXISTS chart_scores_prev`
- `RENAME TABLE chart_scores TO chart_scores_prev, chart_scores_stage TO chart_scores`
- 새 `chart_scores_stage`를 다시 생성

즉, 문서적으로 예쁘게 포장한 게 아니라 실제 publish 경로의 중심 primitive가 `RENAME TABLE`입니다.  
rollback도 alias만 되돌리는 수준이 아니라 `rollbackPublishedTable()`로 `chart_scores_prev`를 다시 `chart_scores`로 복원하고, 실패한 published 테이블은 `chart_scores_failed`로 분리합니다.

### 3-3. Elasticsearch도 same-index rebuild가 아니라 candidate index + alias publish로 바꿨습니다

MySQL만 candidate/published를 나누고 ES를 same-index rebuild로 유지하면, 검색 계층에서 partial publish가 다시 생깁니다.

그래서 ES도 같은 publish semantics 아래로 옮겼습니다.

- candidate index 이름은 `chart.search.index-name + "_" + normalize(version)` 규칙으로 생성합니다.
- 배치는 published index가 아니라 candidate index를 rebuild 합니다.
- 검증이 끝나기 전까지 published alias는 그대로 둡니다.
- publish 시점에만 alias를 candidate index로 전환합니다.

현재 alias 이름은 `ChartPublishProperties.resolveAliasName()`으로 결정되고, 별도 설정이 없으면 `${baseIndexName}_published`를 사용합니다.

이 구조 덕분에 ES는 "계산 중인 인덱스"와 "현재 공개 인덱스"를 분리할 수 있게 됐습니다.  
즉, generate 단계에서 candidate index를 만들어도, publish pointer와 alias가 움직이기 전까지는 사용자 API가 절대 그 결과를 보지 않습니다.

### 3-4. Redis 응답 캐시와 `lastUpdated`도 published version 이후에만 움직이도록 재정의했습니다

partial publish는 데이터 저장소만의 문제가 아닙니다.  
Redis 응답 캐시와 메타데이터가 먼저 바뀌어도 사용자 입장에서는 잘못된 공개입니다.

그래서 publish 모드에서는 cache/meta semantics를 이렇게 다시 정의했습니다.

- 응답 캐시 키는 `chart:v1:{publishedVersion}:...` 형태의 version-aware namespace를 사용합니다.
- `ChartPublishedVersionService`는 Redis cached version을 우선 읽되, 실패하면 `chart_publish_state.current_version`으로 fallback 합니다.
- `ChartLastUpdatedService`는 Redis 메타데이터를 우선 읽되, publish 모드에서는 `chart_publish_state.logical_as_of_at`를 authoritative source로 사용합니다.

즉, `lastUpdated`는 더 이상 wall clock update time이 아닙니다.  
현재 공개된 스냅샷이 어떤 논리 시점을 반영한 차트인지를 설명하는 값입니다.

이 차이가 중요한 이유는 legacy 경로와 publish 경로의 freshness semantics가 실제로 다르기 때문입니다.

- legacy
  - `chart_scores.max(last_updated)` 또는 `now()`에 가까운 값이 노출될 수 있습니다.
- publish mode
  - `logical_as_of_at`가 `lastUpdated`의 기준입니다.

즉, 같은 `lastUpdated`라는 필드라도 publish mode에서는 "현재 공개된 snapshot의 logical as-of"를 의미하도록 교정했습니다.

### 3-5. validation과 rollback orchestration을 붙여 publish를 절차가 아니라 계약으로 닫았습니다

generate와 publish 사이에 validation이 없으면, candidate를 만들었다는 사실만으로 공개가 흘러갈 수 있습니다.  
그래서 `ChartPublishOrchestratorService`가 publish를 아래 계약으로 묶습니다.

generate
- candidate version 생성
- `chart_scores_stage` 준비
- candidate ES index 이름 확정
- `chart_publish_state`에 candidate 등록

validate
- MySQL candidate row count가 0보다 커야 합니다.
- MySQL row count와 ES doc count가 같아야 합니다.
- candidate ES index가 실제 검색 가능한 상태여야 합니다.

publish
- `chart_publish_state.status = PUBLISHING`
- MySQL projection swap
- ES alias publish
- `chart_publish_state.markPublished(version)`
- Redis published version cache 반영
- chart response cache eviction
- `lastUpdated = logical_as_of_at` 반영

rollback
- MySQL published projection을 `previous_version` 기준으로 복구
- ES alias를 이전 index로 복구
- `chart_publish_state`를 previous version/logical_as_of_at로 되돌림
- Redis published version, chart cache, `lastUpdated`를 이전 기준으로 맞춤

중요한 점은 validation 실패 시 `current_version`이 절대 바뀌지 않는다는 것입니다.  
실제 코드에서도 `cacheEvictionStep`은 validation이 실패하면 `markFailed("VALIDATION_FAILED", ...)`만 남기고 예외를 던지며 종료합니다. 이때 alias publish나 Redis/meta 갱신은 호출되지 않습니다.

rollback도 과장하면 안 됩니다.  
현재 구조는 alias만 되돌리는 수준이 아니라, MySQL projection, ES alias, `chart_publish_state`, Redis published version, `lastUpdated`, 응답 캐시까지 함께 복구하는 "최소 복구 뼈대"까지는 닫혀 있습니다. 다만 운영 runbook과 자동 smoke validation까지 완성한 것은 아닙니다.

### 3-6. legacy 경로와 publish 경로가 공존하도록 feature toggle을 뒀습니다

이 구조를 한 번에 기본 경로로 갈아타는 것은 운영상 위험했습니다.  
그래서 `chart.publish.enabled`를 기준으로 legacy 경로와 publish 경로를 공존시켰습니다.

- legacy mode
  - `chart_scores` direct upsert
  - same-index ES rebuild
  - legacy cache/meta semantics
- publish mode
  - candidate version 생성
  - stage projection 적재
  - candidate ES rebuild
  - validation
  - publish pointer 전환
  - published version serve

이 toggle은 단순한 안전장치가 아니라 운영 전략입니다.  
기존 경로를 유지한 채 publish semantics를 검증하고, runtime에서 publish mode를 켜기 전까지 리스크를 단계적으로 통제할 수 있게 해주기 때문입니다.

---

## 4. 핵심 성과

| 관점 | 측정값 | 의미 |
| --- | --- | --- |
| full batch baseline | **11.7859분** | 500만 건 합성 데이터 기준 full rebuild 자체가 어느 정도 비용인지 보여주는 baseline입니다. |
| publish end-to-end | **13.0317분** | generate -> validate -> publish -> serve 전체를 태운 실제 publish 절차 비용입니다. |
| publish visibility latency | **296.03ms** | `publishedAt` 이후 API가 새 published version을 반환하기까지의 가시성 지연입니다. |
| 공개 일관성 검증 | **DB state / Redis / ES alias / API 동일 version** | publish correctness가 여러 저장소에서 실제로 맞물리는지 확인한 결과입니다. |
| 메타데이터 의미 교정 | **`lastUpdated = logical_as_of_at`** | wall clock이 아니라 공개된 snapshot의 논리 시점을 노출하도록 의미를 고정했습니다. |

이 표에서 full batch baseline과 publish end-to-end를 함께 두는 이유는 분명합니다.

- full batch baseline은 "차트를 계산하는 비용"을 보여줍니다.
- publish end-to-end는 "그 결과를 안전하게 공개하는 데 드는 전체 비용"을 보여줍니다.

이 문서에서 중요한 것은 절대 시간 자랑이 아닙니다.  
오히려 아래 사실을 설명할 수 있게 됐다는 점이 더 중요합니다.

- `publishedAt`과 `apiVisibleAt`은 같은 시각이 아니므로 visibility latency를 따로 봐야 합니다.
- publish 성공이란 단순히 배치가 끝난 것이 아니라, DB state, ES alias, Redis/meta, API가 모두 같은 version을 가리키는 상태를 뜻합니다.
- `lastUpdated`도 "언제 배치가 끝났는가"가 아니라 "지금 공개된 snapshot이 어떤 논리 시점을 반영하는가"를 보여주게 됐습니다.

즉, 성과는 "차트를 빨리 만들었다"가 아니라, **안전한 공개 절차가 실제로 먹히는지 검증 가능한 수준으로 만들었다**는 데 있습니다.

---

## 5. 현재 구조

이 구조의 핵심은 차트 데이터를 여러 저장소에 복제한 것이 아니라, candidate와 published를 분리한 뒤 `chart_publish_state` 하나로 현재 공개 버전을 정의한 것입니다.

```text
release_rating_summary
  -> generate candidate version
  -> chart_scores_stage
  -> candidate Elasticsearch index
  -> validation
  -> MySQL publish swap + ES alias publish
  -> chart_publish_state.current_version / logical_as_of_at
  -> Redis published-version / lastUpdated / response cache
  -> API serve published version only
```

이 다이어그램은 단순 처리 순서가 아니라 publish semantics 자체를 보여줍니다.

- generate
  - 아직 공개되지 않은 candidate를 만듭니다.
- validate
  - 공개 가능한 최소 안전성을 확인합니다.
- publish
  - 공개 기준점과 라우팅 대상을 전환합니다.
- serve
  - 사용자 API는 항상 published version만 봅니다.

즉, 여러 저장소가 존재해도 "지금 공개된 차트"는 `chart_publish_state.current_version`과 그 version이 가리키는 `logical_as_of_at`로 한 번에 정의됩니다.

---

## 6. 최종적으로 얻은 것

- 차트 결과를 계산 완료와 동시에 공개하는 대신, versioned snapshot publish 문제로 다시 정의했습니다.
- `chart_publish_state`를 authoritative publish pointer로 세워 여러 저장소의 공개 기준을 한 점으로 모았습니다.
- MySQL projection, ES index, Redis cache/meta를 candidate와 published로 분리해 partial publish를 통제했습니다.
- validation 실패 시 `current_version`이 바뀌지 않고, publish 실패 시 이전 version으로 복구하는 최소 rollback 뼈대를 구현했습니다.
- API가 언제나 published version만 보도록 version-aware cache/meta와 read path를 정리했습니다.
- `lastUpdated`를 wall clock이 아니라 published snapshot의 `logical_as_of_at`로 교정해 메타데이터 의미도 정리했습니다.

결국 이 작업의 본질은 ES alias를 도입한 것이 아니라, **여러 저장소에 걸친 차트 공개를 같은 published version으로 정의하고 노출하는 안전한 publish 파이프라인을 만든 것**입니다.

---

## 7. 남겨둔 것

- validation은 아직 최소 blocking gate 수준입니다.
  - row count, doc count, searchable 여부는 보지만, 도메인 품질 검증까지는 닫지 않았습니다.
- rollback은 "최소 복구 뼈대"까지는 구현됐지만, 운영 runbook과 자동 smoke verification까지 완성한 것은 아닙니다.
- freshness semantics는 아직 남은 과제입니다.
  - 실측에서는 새 published version의 `logical_as_of_at`가 이전 legacy 버전보다 60초 이른 사례가 있었습니다.
  - 이는 consistency failure가 아니라, legacy의 wall clock 중심 의미와 publish mode의 logical snapshot 의미가 공존하면서 생긴 해석 차이입니다.
- feature toggle이 켜지지 않은 환경에서는 여전히 legacy semantics가 동작할 수 있으므로, runtime mode 해석도 문서와 설정을 함께 봐야 합니다.

이번 단계의 목표는 완성형 versioned snapshot platform이 아니었습니다.  
여러 저장소에 걸친 차트 공개를 partial publish 없이 통제할 수 있는 최소 publish 아키텍처를 코드로 닫는 것이 목표였습니다.

---

## 8. 관련 코드

아래 코드는 publish semantics를 실제로 구현한 핵심 파일들입니다.

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

아래 문서는 이 publish 파이프라인의 upstream 집계와 downstream 서빙 경계를 함께 보여주는 공개 포트폴리오 문서입니다.

- [평점 조회와 차트 배치가 함께 쓰는 공통 집계 계층 만들기](./rating-aggregation.md)
- [차트 API 서빙을 Redis 캐시, Elasticsearch 검색, MySQL fallback으로 안정화하기](./chart-serving.md)

---

## 10. 한 줄 요약

차트 계산 결과를 바로 덮어쓰는 대신 `generate -> validate -> publish -> serve`를 분리하고, `chart_publish_state`를 authoritative publish pointer로 세워 MySQL projection, Elasticsearch, Redis, 메타데이터, API가 모두 같은 published version을 가리키도록 만든 안전한 publish 파이프라인입니다.
