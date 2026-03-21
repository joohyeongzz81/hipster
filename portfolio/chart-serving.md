# 차트 API 응답 병목을 Redis 캐시, Elasticsearch 검색, MySQL fallback으로 줄이기

> 차트 API는 `chart_scores` 정렬, 릴리즈 정보 조인, JSON 필터, 공통 메타데이터 조회가 한 경로에 몰려 있어 비쌌습니다. 이 문서는 Redis 응답 캐시, Elasticsearch 검색, MySQL fallback, 메타데이터 분리로 그 병목을 줄인 과정을 다룹니다.

핵심은 Elasticsearch를 붙였다는 사실보다, 차트 API를 느리게 만들던 구간을 나눠 실제 응답 시간을 줄인 데 있습니다.

---

## 1. 문제 상황

차트 조회는 `chart_scores`를 기준으로 정렬하고 있었습니다.  
문제는 응답을 만들기 위해 릴리즈 정보를 계속 조인하거나 하이드레이트(hydrate, 응답 조립용 객체를 다시 읽어 채우는 단계)해야 했고, 장르, 디스크립터, 언어 같은 다중값 조건은 JSON 컬럼 필터에 크게 의존하고 있었다는 점입니다.

문제는 단순히 “조회가 느리다”에서 끝나지 않았습니다.

- 차트는 단순 `Top` 전용 API가 아니라, `bayesian_score DESC` 기준으로 필터 결과를 정렬해 보여주는 검색성 조회 경로였습니다.
- JOIN 비용을 줄이기 위해 `chart_scores` 쪽에 값을 더 비정규화했지만, 그 결과 비정규화 폭이 커졌고 JSON 필터 한계는 그대로 남았습니다.
- 반복 요청이 많아서 cache hit 경로가 중요했지만, miss 경로 자체가 너무 비쌌습니다.
- Redis나 Elasticsearch를 붙여도, 그 둘이 실패하는 순간 API hard failure로 이어지면 운영 가능한 구조라고 보기 어려웠습니다.
- 검색이 빨라져도 `version`, `lastUpdated` 같은 공통 메타데이터 경로가 느리면 실제 API는 여전히 느릴 수 있었습니다.

즉, 당시 차트 API의 진짜 문제는 비정규화 폭이 커진 읽기 테이블, JSON 필터, 메타데이터 병목 때문에 실제 응답 경로가 여전히 비쌌다는 점이었습니다.  
그래서 `chart_scores` 중심 조회 경로를 캐시·검색·fallback·메타데이터 단계로 더 잘게 나눌 필요가 있었습니다.

---

## 2. 구조적 판단

제가 먼저 고정한 중심축은 아래 한 문장이었습니다.

> 차트 서빙의 핵심은 `chart_scores`를 더 중심에 두고, 그 위에 남아 있던 병목을 더 잘게 나누는 것입니다.

`chart_scores`는 차트 정렬과 필터링에 필요한 값을 담는 읽기 테이블입니다.  
실제 엔티티에는 `bayesian_score`, `weighted_avg_rating`, `total_ratings`, `is_esoteric`, `genre_ids`, `descriptor_ids`, `release_type`, `release_year`, `location_id`, `languages`, `last_updated`가 들어 있고, 차트 서빙은 이 테이블을 중심으로 움직입니다.

그 위에서 서빙 책임을 아래처럼 나눴습니다.

- `chart_scores`
  - 배치가 만들어 둔 기준 테이블입니다.
- Redis 응답 캐시
  - `TopChartResponse` 전체를 캐시해 반복 요청을 가장 앞단에서 흡수합니다.
- Elasticsearch
  - cache miss 시 filter + sort 조건으로 `releaseId` 후보를 빠르게 찾는 검색 레이어입니다.
- MySQL fallback
  - Redis/ES 장애 시 `chart_scores` 기반 동적 조회로 API를 살리는 생존 경로입니다.
- `ChartPublishedVersionService` / `ChartLastUpdatedService`
  - `version`, `lastUpdated`를 따로 읽어 검색 외 병목이 API 전체를 가리지 않게 합니다.

여기서 중요한 점은 두 가지였습니다.

- Redis + ES는 빠른 기본 경로이고, MySQL fallback은 비싸지만 API hard failure를 막는 안전망입니다.
- `version`과 `lastUpdated`도 실제 응답 시간에 영향을 주는 별도 경로라서, 검색 경로와 분리해 다뤄야 했습니다.

여기서 ES를 주 경로로 올린 이유도 분명했습니다. 다중값 필터, 정렬, 페이지네이션이 겹치는 구간에서는 MySQL 동적 조회를 안정적인 기본 경로로 유지하기 어려웠고, 그래서 ES를 검색 주 경로로, MySQL은 장애 시 생존 경로로 분리했습니다.

---

## 3. 해결 과정

### 3-1. `chart_scores`에 필요한 값을 더 넣어 JOIN 비용을 먼저 줄였습니다

차트 조회는 `chart_scores`를 기준으로 정렬하고 있었지만, 실제 응답을 만들기 위해 릴리즈 정보를 계속 조인하거나 다시 읽어야 했습니다.  
그래서 먼저 차트 서빙에 반복적으로 필요한 값을 `chart_scores` 쪽에 더 비정규화해 JOIN 비용을 줄였습니다.

이 단계에서 바뀐 것은 단순 조회 성능만이 아닙니다.

- 차트 결과 정렬과 필터링을 `chart_scores`에 더 밀어 넣었습니다.
- 릴리즈 정보 조인 비용을 줄이면서 응답 조립 경로를 단순하게 만들었습니다.
- 하지만 비정규화 폭이 커진 만큼 JSON 다중값 필터와 정렬 한계는 그대로 남았습니다.
- 이후 Redis, Elasticsearch, MySQL fallback도 모두 같은 읽기 테이블을 기준으로 설계할 수 있게 됐습니다.

즉, 이 단계는 차트 서빙에 필요한 값을 `chart_scores` 쪽으로 더 모아 응답 경로를 가볍게 만드는 작업이었습니다.

### 3-2. 반복 요청은 Redis 응답 캐시가 `TopChartResponse` 전체를 흡수하게 했습니다

`ChartService.getCharts()`는 먼저 `ChartCacheKeyGenerator`로 cache key를 만들고, Redis에서 JSON으로 저장된 `TopChartResponse`를 그대로 읽습니다. hit면 역직렬화해서 바로 반환하고, miss거나 Redis read가 실패했을 때만 아래 검색 경로로 내려갑니다.

현재 응답 캐시의 성격은 아래와 같습니다.

- key prefix는 `chart:v1:`입니다.
- publish mode에서는 `chart:v1:{publishedVersion}:...` 형태로 버전별 캐시 키를 사용합니다.
- 필터 파라미터는 정렬된 문자열로 직렬화되고, page가 key에 포함됩니다.
- 캐시 TTL은 7일입니다.

핵심은 “차트 결과 일부”가 아니라 **API 응답 전체**를 캐시한다는 점입니다.  
이 구조 덕분에 반복 요청은 수십 ms 수준으로 흡수됐고, publish 이후에도 새 버전과 이전 버전 캐시가 섞이지 않도록 버전별 캐시 키를 나눌 수 있었습니다.

### 3-3. cache miss는 ES 검색 + DB 조회로 처리하고, 실패 시 MySQL fallback으로 살아남게 했습니다

cache miss 시 `ChartSearchService`는 Elasticsearch에서 먼저 `releaseId` 목록만 찾습니다.  
여기서 ES는 응답을 직접 만드는 저장소가 아니라, `bayesianScore DESC` 정렬 기준으로 후보 `releaseId`를 빠르게 찾는 검색 가속 레이어입니다.

실제 검색 조건도 코드상 꽤 구체적입니다.

- 기본적으로 `includeEsoteric != true`면 `isEsoteric=false`를 강제합니다.
- `genreIds`, `descriptorId`, `locationId`, `language`, `releaseType`, `year`를 Bool filter로 붙입니다.
- 결과는 `bayesianScore DESC`로 정렬합니다.
- publish mode면 published alias를, 아니면 legacy index를 읽습니다.

그 다음 `ChartService`는 `chartScoreRepository.findAllWithReleaseByReleaseIds(...)`로 `ChartScore`와 `Release`를 함께 읽어 응답에 필요한 정보를 채웁니다.  
이후 DB가 반환한 순서를 그대로 쓰지 않고, ES가 준 `releaseId` 순서를 기준으로 다시 배열해 최종 응답 순서를 복원합니다.

즉, 기본 miss 경로는 아래 순서가 정확합니다.

1. ES에서 filter + sort 기준으로 `releaseId` 검색
2. DB에서 `chart_scores`와 `Release`를 조회
3. `ChartResponseAssembler`가 artist 이름을 별도로 조회해 `TopChartResponse(chartType, version, lastUpdated, entries)` 조립

여기서도 중요한 포인트는, **ES가 `releaseId` 후보 검색만 맡고 최종 응답은 끝까지 DB 조회와 조립으로 완성된다는 점**입니다.  
응답 조립은 끝까지 `chart_scores`와 DB 조회를 기준으로 이뤄집니다.

그리고 ES 조회가 실패하면 `ChartService`는 예외를 그대로 던지지 않고 `chartScoreRepository.findChartsDynamic(...)`로 내려갑니다.  
이 fallback query도 raw `ratings`가 아니라 `chart_scores`를 읽지만, `JSON_CONTAINS` 기반 동적 필터와 `bayesian_score DESC` 정렬을 다시 수행하므로 결코 싼 경로는 아닙니다.

그래서 MySQL fallback은 빠른 경로가 아니라, **Redis/ES 장애가 곧바로 API hard failure가 되지 않게 만드는 생존 경로**로 설명하는 것이 맞습니다.  
중요한 점은 fallback으로 내려가더라도 응답 조립은 같은 `ChartPublishedVersionService`, `ChartLastUpdatedService`, `ChartResponseAssembler`를 거치므로 `version`과 `lastUpdated` 기준은 유지된다는 것입니다.

### 3-4. 검색만이 아니라 `version`과 `lastUpdated`도 병목으로 보고 분리했습니다

실제 API 기준으로 보면 검색 결과만 빠르다고 끝나지 않습니다.  
차트 응답에는 entries뿐 아니라 `version`, `lastUpdated`도 함께 들어가고, 이 경로가 느리면 검색을 아무리 줄여도 API 전체는 느릴 수 있습니다.

현재 구현은 이 메타데이터를 별도 서비스로 읽습니다.

- `ChartPublishedVersionService`
  - publish mode에서는 Redis key `chart-meta:published-version:v1`를 먼저 읽습니다.
  - Redis miss나 read failure 시 `chart_publish_state.current_version`으로 fallback합니다.
  - publish mode가 꺼져 있으면 `legacy`를 반환합니다.
- `ChartLastUpdatedService`
  - Redis key `chart-meta:last-updated:v1`를 먼저 읽습니다.
  - publish mode에서는 `chart_publish_state.logical_as_of_at`를 authoritative source로 봅니다.
  - legacy mode에서는 `chart_scores.max(last_updated)`를 읽고, 값이 없으면 최종 fallback으로 `LocalDateTime.now()`를 사용합니다.

핵심은 검색 최적화만으로는 API가 빨라지지 않았고, 실제 병목이 `lastUpdated` 메타데이터 경로에도 있었다는 점입니다.  
CH5 시점 raw response에서는 `searchMillis=78ms`인데 `lastUpdatedMillis=5,073ms`였고, 결국 API 전체 시간은 ES가 아니라 `lastUpdated`가 가리고 있었습니다.

그래서 `lastUpdated`를 Redis 메타데이터 키로 분리하고, publish mode에서는 `logical_as_of_at`를 읽도록 바꾸면서 검색 경로를 줄인 효과가 실제 API 응답 시간에도 드러나기 시작했습니다.

publish mode에서는 이 메타데이터 경로가 캐시 키와 같이 움직입니다.

- publish 후 `ChartPublishOrchestratorService`가 published version을 Redis에 캐시합니다.
- `chart:v1:*` namespace를 비워 이전 캐시를 끊습니다.
- 같은 시점에 `logical_as_of_at`를 `chart-meta:last-updated:v1`에 캐시합니다.

즉, publish mode에서는 응답 캐시와 메타데이터가 같은 차트 기준을 보도록 읽기 경계를 맞춘 것입니다.

---

## 4. 핵심 성과

| 시나리오 | 측정값 | 이 문서에서 의미하는 것 |
| --- | --- | --- |
| 정규화 JOIN 중심 차트 조회 | **65,442ms** | `chart_scores` 정렬에 필요한 릴리즈 메타데이터와 다중값 조건을 정규화 테이블 JOIN으로 계속 읽던 baseline입니다. |
| `chart_scores` 비정규화 강화 후 차트 조회 | **12,069ms** | JOIN 비용을 줄이고 `chart_scores` 중심 조회 경로로 더 밀어 넣은 1차 개선입니다. |
| Redis hit 응답 시간 | **16~23ms** | 반복 요청은 응답 캐시가 거의 전부 흡수하게 만들었습니다. |
| ES miss 응답 시간 | **4,790ms -> 146ms** | ES 검색만이 아니라 메타데이터 병목 제거까지 포함한 실제 API 기준 miss 경로 개선입니다. |
| `lastUpdated` 메타데이터 조회 시간 | **5,073ms -> 1ms** | 검색보다 느리던 공통 메타데이터 병목을 별도 경계로 분리해 제거했습니다. |

이 수치는 모두 local synthetic benchmark 기준입니다.  
주요 수치는 500만 건 규모 synthetic chart dataset에서 대표 필터 조합 `S4`, `G2`를 기준으로 측정했고, 기본 응답은 `page=0`, `size=20` 시나리오를 사용했습니다.  
Redis hit와 miss는 분리했고, ES miss 수치는 cache miss 상태에서 metadata 조회까지 포함한 전체 API 응답 시간을 기준으로 봤습니다.  
중요한 것은 단순히 “조회가 빨라졌다”가 아니라, **정규화 JOIN, cache miss, 메타데이터 조회**처럼 실제 차트 API를 가리던 느린 구간을 각각 분리해 줄였다는 점입니다.

특히 이 문서의 차별점은 ES miss 수치 하나가 아니라, `lastUpdatedMillis` 개선을 함께 보여줄 수 있다는 데 있습니다.  
검색 엔진을 붙여도 메타데이터 경로가 느리면 API 전체는 느릴 수 있었고, 그래서 이 문서는 검색 레이어 도입기보다 **실제 응답 경로 전체의 병목을 줄인 서빙 계층 개선기**로 읽혀야 합니다.

---

## 5. 현재 서빙 구조

이 구조의 핵심은 차트를 ES가 직접 서빙하는 API로 바꾼 것이 아니라, 느린 응답 경로를 `chart_scores`, Redis, ES, MySQL fallback, 메타데이터 경로로 나눠 관리하는 서빙 계층으로 만든 것입니다.

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
- `version`과 `lastUpdated`도 별도 메타데이터 경로로 관리해, 검색 외 병목이 API 전체를 가리지 않게 했습니다.

---

## 6. 최종적으로 얻은 것

- 차트 API의 중심 읽기 경로를 정규화 JOIN에서 `chart_scores` 중심 서빙 구조로 더 밀어 넣었습니다.
- Redis는 응답 전체 캐시, ES는 `releaseId` 검색, MySQL fallback은 장애 시 생존 경로라는 역할 분리가 선명해졌습니다.
- `lastUpdated`를 검색 외부의 메타데이터 경로로 분리해, 검색 성능 개선 효과가 실제 API 응답 시간에 드러나게 만들었습니다.
- publish mode에서는 캐시 키와 메타데이터도 version 기준으로 읽히도록 정리할 수 있었습니다.
- 결국 차트 서빙을 “ES를 붙인 API”가 아니라, 읽기 테이블·cache·search·fallback·메타데이터 병목을 나눠 줄인 운영 가능한 read-heavy 서빙 계층으로 설명할 수 있게 됐습니다.

---

## 7. 남겨둔 것

- MySQL fallback은 여전히 비싼 경로입니다. `JSON_CONTAINS` 기반 동적 필터와 정렬이 남기 때문에, 안전망이지 기본 경로가 아닙니다.
- publish mode가 꺼진 환경에서는 여전히 `legacy` version과 legacy cache/meta 기준을 전제로 읽습니다. 특히 legacy `lastUpdated`는 publish mode의 `logical_as_of_at` 기준과 다릅니다.
- 차트 freshness는 결국 upstream `release_rating_summary`와 차트 배치/publish 주기에 종속됩니다. 즉시 반영형 API가 아니라 배치 주기에 맞춰 갱신되는 구조입니다.
- 응답 조립 과정에서는 `chart_scores`만으로 끝나지 않고 `Release` 조회와 artist 이름 조회가 추가로 필요하므로, 검색 외 부하는 계속 관리해야 합니다.

하지만 이번 단계에서 더 먼저 풀어야 했던 문제는 완전한 실시간 검색 플랫폼을 만드는 것이 아니라, **배치가 만든 차트 결과를 실제 API에서 빠르고 끊기지 않게 읽도록 서빙 경로를 정리하는 것**이었습니다.

다음 단계에서 먼저 볼 과제는 MySQL fallback 제거보다, miss 경로에서 남아 있는 `Release` 조회와 artist lookup 비용을 더 줄이는 일입니다.

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

차트 API를 ES 도입기처럼 설명하지 않고, `chart_scores` 기반 조회 경로를 캐시·검색·fallback·메타데이터 단계로 나눠 실제 응답 병목을 줄인 서빙 계층 개선으로 정리했습니다.
