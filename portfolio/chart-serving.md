# 차트 API 서빙을 Redis 캐시, Elasticsearch 검색, MySQL fallback으로 안정화하기

> 차트 API를 request-time 집계가 아니라 배치가 만든 `chart_scores` projection을 읽는 read-heavy 서빙 계층으로 재설계하고, Redis 응답 캐시·Elasticsearch 검색·MySQL fallback·version/`lastUpdated` 메타데이터를 함께 운영 가능한 read path로 정리했습니다.

이 문서의 핵심은 Elasticsearch를 붙였다는 사실이 아닙니다.  
핵심은 차트를 raw `ratings`를 즉석에서 다시 계산하는 API가 아니라, 배치가 만들어 둔 `chart_scores` snapshot을 실제 사용자 API로 빠르고 끊기지 않게 서빙하는 읽기 계층으로 다시 정의한 것입니다.

즉, 이 문서는 차트 publish 자체를 설명하는 문서가 아니라, 공개된 snapshot을 `/api/v1/charts` 응답으로 안정적으로 읽어내는 서빙 계층을 다룹니다.

---

## 1. 문제 상황

초기 차트 조회는 정규화 테이블 JOIN을 직접 수행하는 구조였습니다.  
장르, 디스크립터, 언어 같은 다중 필터가 붙는 순간 JOIN 비용과 정렬 비용이 함께 커졌고, 차트 API는 랭킹 결과를 보여주는 read path임에도 매 요청마다 request-time aggregate처럼 동작했습니다.

문제는 단순히 “조회가 느리다”에서 끝나지 않았습니다.

- 차트는 단순 `Top` 전용 API가 아니라, `bayesian_score DESC` 기준으로 필터 결과를 정렬해 보여주는 검색성 read path였습니다.
- 반복 요청이 많아서 cache hit 경로가 중요했지만, miss 경로 자체가 너무 비쌌습니다.
- Redis나 Elasticsearch를 붙여도, 그 둘이 실패하는 순간 API가 같이 죽으면 운영 가능한 구조라고 보기 어려웠습니다.
- 검색이 빨라져도 `version`, `lastUpdated` 같은 공통 메타데이터 경로가 느리면 실제 API는 여전히 느릴 수 있었습니다.

즉, 당시 차트 API의 진짜 문제는 “검색 엔진이 없다”가 아니라, **차트가 아직도 request-time aggregate처럼 동작하고 있었다**는 점이었습니다.  
해결해야 했던 것은 ES 도입 자체가 아니라, projection·cache·search·fallback·metadata를 하나의 read-heavy 서빙 계층으로 재설계하는 일이었습니다.

---

## 2. 구조적 판단

제가 먼저 고정한 중심축은 아래 한 문장이었습니다.

> 차트는 raw 데이터를 즉석에서 계산하는 API가 아니라, 배치가 만든 `chart_scores` projection을 읽는 도메인입니다.

`chart_scores`는 단순 비정규화 테이블이 아니라, 차트 정렬과 필터링에 필요한 값을 미리 담아 둔 차트 전용 read model입니다.  
실제 엔티티에는 `bayesian_score`, `weighted_avg_rating`, `total_ratings`, `is_esoteric`, `genre_ids`, `descriptor_ids`, `release_type`, `release_year`, `location_id`, `languages`, `last_updated`가 들어 있고, 차트 서빙은 이 projection을 중심으로 움직입니다.

그 위에서 서빙 책임을 아래처럼 나눴습니다.

- `chart_scores`
  - 배치가 만들어 둔 authoritative read model입니다.
- Redis 응답 캐시
  - `TopChartResponse` 전체를 캐시해 반복 요청을 가장 앞단에서 흡수합니다.
- Elasticsearch
  - cache miss 시 filter + sort 조건으로 `releaseId` 후보를 빠르게 찾는 검색 레이어입니다.
- MySQL fallback
  - Redis/ES 장애 시 `chart_scores` 기반 동적 조회로 API를 살리는 생존 경로입니다.
- `ChartPublishedVersionService` / `ChartLastUpdatedService`
  - 사용자가 지금 어떤 snapshot version과 기준 시점을 보고 있는지 설명하는 메타데이터 경계입니다.

여기서 중요한 점은 두 가지였습니다.

- Redis + ES는 빠른 기본 경로이고, MySQL fallback은 비싸지만 API hard failure를 막는 안전망입니다.
- `version`과 `lastUpdated`는 장식용 필드가 아니라, 사용자에게 “지금 어떤 snapshot을 보고 있는가”를 설명하는 응답 semantics입니다.

또한 코드에는 publish mode와 legacy mode가 공존합니다.  
기본 설정은 아직 `chart.publish.enabled=false`라 legacy 경로가 기본 해석이지만, publish mode를 켜면 cache namespace, ES alias, `lastUpdated` semantics가 published snapshot 기준으로 바뀝니다. 이 문서가 둘을 함께 설명하는 이유도 바로 그 차이가 read path semantics를 바꾸기 때문입니다.

---

## 3. 해결 과정

### 3-1. `chart_scores` projection으로 request-time JOIN 구조를 끊었습니다

가장 먼저 한 일은 차트 조회를 정규화 JOIN에서 떼어내고, `release_rating_summary`를 입력으로 배치가 만들어 둔 `chart_scores` projection을 읽게 바꾸는 것이었습니다.

이 단계에서 바뀐 것은 단순 조회 성능만이 아닙니다.

- 차트를 request-time aggregate에서 projection read path로 바꿨습니다.
- 차트 필터링과 정렬 책임을 JOIN 경로가 아니라 `chart_scores` read model로 이동시켰습니다.
- 이후 Redis, Elasticsearch, MySQL fallback도 모두 같은 projection을 기준으로 설계할 수 있게 됐습니다.

즉, 차트 API는 더 이상 raw `ratings`를 읽어 다시 계산하지 않고, 배치가 계산해 둔 차트 snapshot을 서빙하는 계층이 됐습니다.

### 3-2. 반복 요청은 Redis 응답 캐시가 `TopChartResponse` 전체를 흡수하게 했습니다

`ChartService.getCharts()`는 먼저 `ChartCacheKeyGenerator`로 cache key를 만들고, Redis에서 JSON으로 저장된 `TopChartResponse`를 그대로 읽습니다. hit면 역직렬화해서 바로 반환하고, miss거나 Redis read가 실패했을 때만 아래 검색 경로로 내려갑니다.

현재 응답 캐시의 성격은 아래와 같습니다.

- key prefix는 `chart:v1:`입니다.
- publish mode에서는 `chart:v1:{publishedVersion}:...` 형태의 version-aware namespace를 사용합니다.
- 필터 파라미터는 정렬된 문자열로 직렬화되고, page가 key에 포함됩니다.
- 캐시 TTL은 7일입니다.

핵심은 “차트 결과 일부”가 아니라 **API 응답 전체**를 캐시한다는 점입니다.  
이 구조 덕분에 반복 요청은 수십 ms 수준으로 흡수됐고, publish 이후에도 새 버전과 이전 버전 캐시가 섞이지 않도록 namespace를 snapshot version에 맞출 수 있었습니다.

### 3-3. cache miss는 ES 검색 + DB hydrate로 처리하고, 실패 시 MySQL fallback으로 살아남게 했습니다

cache miss 시 `ChartSearchService`는 Elasticsearch에서 먼저 `releaseId` 목록만 찾습니다.  
여기서 ES는 응답을 직접 만드는 저장소가 아니라, `bayesianScore DESC` 정렬 기준으로 후보 `releaseId`를 빠르게 찾는 검색 가속 레이어입니다.

실제 검색 조건도 코드상 꽤 구체적입니다.

- 기본적으로 `includeEsoteric != true`면 `isEsoteric=false`를 강제합니다.
- `genreIds`, `descriptorId`, `locationId`, `language`, `releaseType`, `year`를 Bool filter로 붙입니다.
- 결과는 `bayesianScore DESC`로 정렬합니다.
- publish mode면 published alias를, 아니면 legacy index를 읽습니다.

그 다음 `ChartService`는 `chartScoreRepository.findAllWithReleaseByReleaseIds(...)`로 `ChartScore`와 `Release`를 함께 hydrate합니다.  
이후 DB가 반환한 순서를 그대로 쓰지 않고, ES가 준 `releaseId` 순서를 기준으로 다시 배열해 최종 응답 순서를 복원합니다.

즉, 기본 miss 경로는 아래 순서가 정확합니다.

1. ES에서 filter + sort 기준으로 `releaseId` 검색
2. DB에서 `chart_scores`와 `Release`를 hydrate
3. `ChartResponseAssembler`가 artist 이름을 별도로 조회해 `TopChartResponse(chartType, version, lastUpdated, entries)` 조립

여기서도 중요한 포인트는, **ES가 차트 응답을 직접 서빙하는 것이 아니라는 점**입니다.  
응답 조립은 끝까지 `chart_scores`와 DB hydrate를 기준으로 이뤄집니다.

그리고 ES 조회가 실패하면 `ChartService`는 예외를 그대로 던지지 않고 `chartScoreRepository.findChartsDynamic(...)`로 내려갑니다.  
이 fallback query도 raw `ratings`가 아니라 `chart_scores`를 읽지만, `JSON_CONTAINS` 기반 동적 필터와 `bayesian_score DESC` 정렬을 다시 수행하므로 결코 싼 경로는 아닙니다.

그래서 MySQL fallback은 빠른 경로가 아니라, **Redis/ES 장애가 곧바로 API hard failure가 되지 않게 만드는 생존 경로**로 설명하는 것이 맞습니다.  
중요한 점은 fallback으로 내려가더라도 응답 조립은 같은 `ChartPublishedVersionService`, `ChartLastUpdatedService`, `ChartResponseAssembler`를 거치므로 `version`과 `lastUpdated` semantics는 유지된다는 것입니다.

### 3-4. 검색만이 아니라 `version`과 `lastUpdated`를 별도 메타데이터 경계로 분리했습니다

실제 API 기준으로 보면 검색 결과만 빠르다고 끝나지 않습니다.  
사용자는 차트 entries만 보는 것이 아니라, 지금 어떤 version의 snapshot을 보고 있는지와 그 snapshot이 어떤 시점을 기준으로 공개된 것인지도 함께 봅니다.

현재 구현은 이 메타데이터를 별도 서비스로 읽습니다.

- `ChartPublishedVersionService`
  - publish mode에서는 Redis key `chart-meta:published-version:v1`를 먼저 읽습니다.
  - Redis miss나 read failure 시 `chart_publish_state.current_version`으로 fallback합니다.
  - publish mode가 꺼져 있으면 `legacy`를 반환합니다.
- `ChartLastUpdatedService`
  - Redis key `chart-meta:last-updated:v1`를 먼저 읽습니다.
  - publish mode에서는 `chart_publish_state.logical_as_of_at`를 authoritative source로 봅니다.
  - legacy mode에서는 `chart_scores.max(last_updated)`를 읽고, 값이 없으면 최종 fallback으로 `LocalDateTime.now()`를 사용합니다.

이 분리가 중요했던 이유는 benchmark에서 검색보다 메타데이터가 더 느린 구간이 실제로 관측됐기 때문입니다.  
CH5 시점 raw response에서는 `searchMillis=78ms`인데 `lastUpdatedMillis=5,073ms`였고, 결국 API 전체 시간은 ES가 아니라 `lastUpdated`가 가리고 있었습니다.

그래서 `lastUpdated`를 Redis 메타데이터 키로 분리하고, publish mode에서는 `logical_as_of_at`를 authoritative source로 쓰게 바꾸면서, 검색 계층의 개선 효과가 실제 API 응답 시간에도 드러나기 시작했습니다.

또 publish mode에서는 이 메타데이터 경계가 publish version과 같이 움직입니다.

- publish 후 `ChartPublishOrchestratorService`가 published version을 Redis에 캐시합니다.
- `chart:v1:*` namespace를 비워 이전 캐시를 끊습니다.
- 같은 시점에 `logical_as_of_at`를 `chart-meta:last-updated:v1`에 캐시합니다.

즉, publish mode에서는 **응답 캐시, version, lastUpdated가 같은 snapshot을 가리키도록** read path semantics를 맞춘 것입니다.

---

## 4. 핵심 성과

| 시나리오 | 측정값 | 이 문서에서 의미하는 것 |
| --- | --- | --- |
| 정규화 JOIN 기반 장르 필터 조회 | **65,442ms** | 차트 read path가 아직 request-time aggregate처럼 동작하던 baseline입니다. |
| `chart_scores` projection 기반 조회 | **12,069ms** | request-time JOIN을 projection read로 바꾼 1차 전환 효과입니다. |
| Redis hit 경로 | **16~23ms** | 반복 요청은 응답 캐시가 거의 전부 흡수하게 만들었습니다. |
| ES cache miss 경로 | **4,790ms -> 146ms** | ES 검색만이 아니라 metadata 병목 제거까지 포함한 실제 API 기준 miss 경로 개선입니다. |
| `lastUpdatedMillis` | **5,073ms -> 1ms** | 검색보다 느리던 공통 메타데이터 병목을 별도 경계로 분리해 제거했습니다. |

이 수치는 모두 local synthetic benchmark 기준입니다.  
중요한 것은 단순히 “조회가 빨라졌다”가 아니라, **request-time JOIN -> projection read -> cache/search -> metadata 분리**라는 순서로 어디가 병목이었고 무엇을 나눠야 실제 API가 빨라졌는지를 설명할 수 있게 됐다는 점입니다.

특히 이 문서의 차별점은 ES miss 수치 하나가 아니라, `lastUpdatedMillis` 개선을 함께 보여줄 수 있다는 데 있습니다.  
검색 엔진을 붙여도 metadata 경로가 느리면 API 전체는 느릴 수 있었고, 그래서 이 문서는 검색 레이어 개선기보다 **실제 응답 경로 전체를 닫은 서빙 계층 개선기**로 읽혀야 합니다.

---

## 5. 현재 서빙 구조

이 구조의 핵심은 차트를 ES가 직접 서빙하는 API로 바꾼 것이 아니라, `chart_scores` projection을 중심으로 Redis, ES, MySQL fallback, metadata 경계를 함께 운영하는 read-heavy 서빙 계층으로 만든 것입니다.

```text
/api/v1/charts?page&size
  -> cache key 생성 (`chart:v1:` or `chart:v1:{publishedVersion}:...`)
  -> Redis response cache 조회
  -> cache hit: TopChartResponse 그대로 반환
  -> cache miss:
       -> Elasticsearch search (published alias or legacy index)
       -> releaseId 목록 반환
       -> chart_scores + Release hydrate
       -> artist lookup + response assemble(version, lastUpdated, entries)
       -> Redis cache write

Redis read/write failure
  -> 검색/조립 경로로 계속 진행

ES failure
  -> MySQL fallback query(`chart_scores` dynamic filter + sort)
  -> 같은 metadata/assembler로 응답 조립

metadata
  -> version: Redis published-version -> chart_publish_state.current_version -> legacy
  -> lastUpdated: Redis last-updated -> logical_as_of_at or chart_scores.max(last_updated)
```

이 read path를 한 문장으로 요약하면 이렇습니다.

- 차트는 raw `ratings`를 다시 읽지 않고, 배치가 만든 `chart_scores`를 읽습니다.
- 빠른 기본 경로는 Redis + ES이고, MySQL fallback은 비싸지만 API를 살리는 생존 경로입니다.
- `version`과 `lastUpdated`도 별도 메타데이터 경계로 관리해, 사용자에게 같은 snapshot을 설명할 수 있게 했습니다.

---

## 6. 최종적으로 얻은 것

- 차트 API를 request-time aggregate가 아니라 projection 기반 read path로 재정의했습니다.
- Redis는 응답 전체 캐시, ES는 `releaseId` 검색, MySQL fallback은 장애 시 생존 경로라는 역할 분리가 선명해졌습니다.
- publish mode에서는 cache namespace, ES alias, `version`, `lastUpdated`가 같은 snapshot version을 가리키도록 정렬했습니다.
- `lastUpdated`를 검색 외부의 메타데이터 경계로 분리해, 검색 성능 개선 효과가 실제 API 응답 시간에 드러나게 만들었습니다.
- 결국 차트 서빙을 “ES를 붙인 API”가 아니라, projection·cache·search·fallback·metadata를 함께 읽는 운영 가능한 read-heavy 서빙 계층으로 설명할 수 있게 됐습니다.

---

## 7. 남겨둔 것

- MySQL fallback은 여전히 비싼 경로입니다. `JSON_CONTAINS` 기반 동적 필터와 정렬이 남기 때문에, 안전망이지 기본 경로가 아닙니다.
- publish mode가 꺼진 환경에서는 여전히 `legacy` version과 legacy cache/meta semantics를 전제로 읽습니다. 특히 legacy `lastUpdated`는 publish mode의 `logical_as_of_at` semantics와 다릅니다.
- 차트 freshness는 결국 upstream `release_rating_summary`와 차트 배치/publish 주기에 종속됩니다. 즉시 반영형 시스템이 아니라 snapshot 기반 시스템입니다.
- 응답 조립 과정에서는 `chart_scores`만으로 끝나지 않고 `Release` hydrate와 artist 이름 조회가 추가로 필요하므로, 검색 외 부하는 계속 관리해야 합니다.

하지만 이 비용은 의식적으로 받아들인 것입니다.  
이번 단계의 목표는 완전한 실시간 검색 플랫폼이 아니라, **배치가 만든 차트 snapshot을 캐시·검색·메타데이터·장애 대응까지 포함해 실제 API로 안정적으로 서빙하는 읽기 계층을 닫는 것**이었습니다.

---

## 8. 관련 코드

- `src/main/java/com/hipster/chart/controller/ChartController.java`
- `src/main/java/com/hipster/chart/service/ChartService.java`
- `src/main/java/com/hipster/chart/service/ChartSearchService.java`
- `src/main/java/com/hipster/chart/service/ChartResponseAssembler.java`
- `src/main/java/com/hipster/chart/service/ChartCacheKeyGenerator.java`
- `src/main/java/com/hipster/chart/service/ChartLastUpdatedService.java`
- `src/main/java/com/hipster/chart/publish/service/ChartPublishedVersionService.java`
- `src/main/java/com/hipster/chart/publish/service/ChartPublishStateService.java`
- `src/main/java/com/hipster/chart/service/ChartElasticsearchIndexService.java`
- `src/main/java/com/hipster/chart/repository/ChartScoreRepository.java`
- `src/main/java/com/hipster/chart/repository/ChartScoreRepositoryImpl.java`

---

## 9. 관련 문서

- [평점 조회와 차트 배치가 함께 쓰는 공통 집계 계층 만들기](./rating-aggregation.md)
- [차트 배치 재생성과 공개를 분리해 안전한 publish 파이프라인 만들기](./chart-pipeline.md)

---

## 10. 한 줄 요약

차트 API를 request-time 집계가 아니라 배치가 만든 `chart_scores` projection을 읽는 read-heavy 서빙 계층으로 재설계하고, Redis 응답 캐시·Elasticsearch 검색·MySQL fallback·version/`lastUpdated` 메타데이터를 함께 운영 가능한 read path로 정리했습니다.
