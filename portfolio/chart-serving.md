# 차트 API 읽기 경로를 캐시·검색·fallback·메타데이터로 분리해 응답 병목 줄이기

> 차트 API는 `chart_scores` 정렬, 릴리즈 정보 조회, JSON 필터, 공통 메타데이터 조회가 한 경로에 몰려 있어 응답 비용이 높았습니다.  
> `chart_scores` 기반 읽기 경로를 Redis 응답 캐시, Elasticsearch 검색, MySQL fallback, 메타데이터 경로로 분리해 병목을 줄였습니다.
>
> 모든 수치는 운영 환경이 아니라 로컬 합성 데이터 벤치마크 기준입니다.

## 핵심 성과

- 500만 건 합성 데이터 기준 장르 필터 차트 조회: **65,421ms → 178.37ms**
- 500만 건 합성 데이터 기준 장르 필터 반복 요청 응답: **11,386ms → 16.73ms**
- 500만 건 합성 데이터 기준 차트 갱신 시각 조회: **4,732ms → 1.00ms**

---

## 1. 문제 상황

차트 조회는 `chart_scores`를 기준으로 정렬했지만, 필터링과 응답 조립을 위해 릴리즈 기본 정보, 아티스트, 장르·디스크립터·언어 같은 분류 정보를 함께 읽거나 조인해야 했습니다.

문제는 단순히 "조회가 느리다"에서 끝나지 않았습니다.

- 차트는 단순 상위권 조회 API가 아니라, `bayesian_score DESC` 기준으로 필터 결과를 정렬해 보여주는 검색성 조회 경로였습니다.
- 조인 비용을 줄이기 위해 `chart_scores`에 값을 더 비정규화했지만, JSON 필터 한계는 그대로 남았습니다.
- 반복 요청이 많아 캐시 적중 경로가 중요했는데, 캐시 미스 경로 자체가 너무 비쌌습니다.
- Redis나 Elasticsearch가 실패하는 순간 API 전체 실패로 이어지는 구조는 운영 가능한 설계가 아니었습니다.
- 검색이 빨라져도 갱신 시각 같은 공통 메타데이터 조회가 느리면 API 전체는 여전히 느렸습니다.

결국 차트 API의 문제는, 읽기 테이블 하나보다도 응답 경로 전체에 병목이 겹쳐 있었다는 점이었습니다. `chart_scores` 조회, 릴리즈·분류 정보 보강, 캐시 미스, 메타데이터 조회를 분리해 다뤄야 했습니다.

---

## 2. 설계 원칙

> 차트 서빙의 핵심은 `chart_scores`를 중심 읽기 테이블로 두고, 그 위에 남아 있는 병목을 더 잘게 나누는 것입니다.

서빙 책임은 아래처럼 분리했습니다.

- `chart_scores`
  - 배치가 만들어 둔 기준 읽기 테이블입니다.
- Redis 응답 캐시
  - 최종 응답 전체를 캐시해 반복 요청을 최전선에서 흡수합니다.
- Elasticsearch
  - 캐시 미스 시 필터 + 정렬 조건으로 릴리즈 ID 후보를 빠르게 찾는 검색 계층입니다.
- MySQL fallback
  - Redis나 Elasticsearch 장애 시 `chart_scores` 기반 동적 조회로 API를 살리는 생존 경로입니다.
- 메타데이터 경로
  - 갱신 시각 같은 공통 메타데이터를 별도 경로로 분리해 메타데이터 병목이 API 전체를 가리지 않도록 처리합니다.

- Redis와 Elasticsearch는 빠른 기본 경로이고, MySQL fallback은 비싸지만 API를 끊기지 않게 만드는 안전망입니다.
- 갱신 시각 같은 공통 메타데이터도 실제 응답 시간에 영향을 주는 독립된 병목이므로, 검색 경로와 분리해 다뤄야 했습니다.

다중값 필터, 정렬, 페이지네이션이 겹친 조건은 MySQL 동적 조회만으로 안정적인 주 경로를 만들기 어려웠습니다. 그래서 Elasticsearch를 검색 주 경로로 두고, MySQL은 fallback으로 남겼습니다.

---

## 3. 해결 과정

수치는 모두 로컬 합성 데이터 벤치마크 기준입니다. 절대 성능보다, 같은 API와 같은 데이터셋에서 병목이 어디로 이동했고 각 단계가 응답 시간에 어떤 영향을 줬는지 비교했습니다. 측정 환경은 문서 끝 부록에 정리했습니다.

본문에는 대표 시나리오만 싣고, 전체 시나리오 목록과 측정표는 부록에 실었습니다. 별도 표기가 없으면 모두 `page=0`, `size=20`, 같은 응답 DTO 기준입니다.

| 시나리오 | 조건 | 의도 |
| --- | --- | --- |
| `S1_BASELINE` | 필터 없음 | 기본 조회 경로 |
| `S2_RELEASE_TYPE_ALBUM` | `releaseType=ALBUM` | 단일값 필터 최적화 효과 |
| `S4_GENRE_1` | `genreIds=[1]` | JSON 필터 병목 대표 |
| `G2_GENRES_1_7` | `genreIds=[1,7]` | 복합 JSON 필터 대표 |

### 3-0. 지표 읽는 법

| 용어 | 뜻 |
| --- | --- |
| `*Millis` | 단일 실행 1회에서 서버 내부 단계를 계측한 시간(ms)입니다. |
| `*Avg` | 같은 시나리오를 여러 번 반복 실행했을 때의 평균 시간(ms)입니다. |
| `wallMillis`, `wallAvg` | 요청 시작부터 응답 수신까지의 실제 경과 시간입니다. 실제 API 체감에 가장 가깝습니다. |
| `totalMillis`, `totalAvg` | 검색, 메타데이터 조회, 응답 조립 등 서버 내부 처리 전체를 합산한 시간입니다. |
| `searchMillis`, `searchAvg` | 필터 + 정렬 조건으로 릴리즈 ID 후보를 찾는 단계의 시간입니다. |
| `hydrateMillis`, `hydrateAvg` | 찾은 릴리즈 ID로 `chart_scores`와 릴리즈를 다시 읽고, 검색 결과 순서대로 복원하는 단계의 시간입니다. |
| `lastUpdatedMillis`, `lastUpdatedAvg` | 공통 메타데이터인 갱신 시각(`lastUpdated`)을 읽는 시간입니다. |
| `assembleMillis`, `assembleAvg` | 최종 응답을 조립하고 아티스트 이름을 채우는 시간입니다. |
| `cold miss` | 관련 캐시를 비운 뒤 처음 수행한 요청입니다. |
| `miss` | 해당 캐시 키가 비어 있어 검색과 응답 조립을 모두 거친 요청입니다. |
| `hit` | 캐시에 저장된 응답을 바로 반환한 요청입니다. |
| `Handler_read_key` | 인덱스 키를 통해 읽은 횟수입니다. |
| `Handler_read_next` | 인덱스에서 다음 레코드를 순차적으로 읽은 횟수입니다. |
| `Handler_read_rnd_next` | 랜덤/풀스캔 성격의 읽기 횟수입니다. |
| `Sort_merge_passes` | 정렬 과정에서 merge pass가 발생한 횟수입니다. |

### 3-1. `chart_scores` 비정규화를 강화해 조인 병목을 먼저 줄였습니다

첫 단계는 정규화 조인으로 묶여 있던 조회 비용을 `chart_scores` 쪽으로 옮기는 일이었습니다. 기준 쿼리는 릴리즈 테이블을 중심으로, 조건에 따라 장르·디스크립터·언어 테이블까지 조인하는 구조였습니다. 정렬과 기본 필터에 필요한 점수, 릴리즈 타입, 발매 연도, 지역, 장르·디스크립터·언어 필터용 값은 `chart_scores`에 넣고, 최종 응답에 필요한 릴리즈 제목·발매일과 아티스트 이름은 hydrate와 응답 조립 단계에 남겼습니다.

| 시나리오 | CH1 정규화 조인 | CH2 비정규화 읽기 모델 | 변화 |
| --- | --- | --- | --- |
| `S1_BASELINE` | `85,652ms` | `9,303ms` | 무필터 기본 경로 개선 |
| `S2_RELEASE_TYPE_ALBUM` | `54,488ms` | `8,864ms` | 단일값 필터 개선 |
| `S4_GENRE_1` | `65,421ms` | `12,034ms` | 장르 필터 개선 |
| `G2_GENRES_1_7` | `61,634ms` | `14,089ms` | 멀티 장르 필터 개선 |

- 정규화 조인 병목은 확실히 줄었습니다.
- 하지만 좁거나 복잡한 조건에서는 비정규화 폭이 커진 읽기 테이블만으로 충분하지 않았고, 이 한계는 부록의 전체 결과에서도 확인할 수 있습니다.

MySQL 지표도 같은 방향을 보여줬습니다. `S4_GENRE_1` 기준으로 조인 관련 읽기는 크게 줄었습니다.

- `Handler_read_key`: `15,920,719 -> 67`
- `Handler_read_next`: `8,955,317 -> 3`

반면 풀스캔과 정렬 성격은 그대로 남았습니다.

- `Handler_read_rnd_next`: `14,633,960 -> 10,001,006`
- `Sort_merge_passes`: `592 -> 699`

즉, 이 단계는 조인 비용을 줄였지만, JSON 필터와 남은 정렬 비용까지 해결한 단계는 아니었습니다.

**이 단계의 결론:** 조인 병목은 줄였지만, JSON 필터와 정렬 비용은 여전히 주 병목으로 남았습니다.

### 3-2. Querydsl과 인덱스 전략으로 MySQL 최적화를 먼저 진행했습니다

두 번째 단계에서는 쿼리 자체를 다시 구성했습니다. Querydsl로 조건식을 분리하고, 단일값 필터와 정렬 경로에 맞춘 인덱스를 적용해 MySQL 안에서 먼저 최대한 줄였습니다.

| 시나리오 | CH2 비정규화 모델 | CH3 쿼리 재구성 + 인덱스 | 변화 |
| --- | --- | --- | --- |
| `S1_BASELINE` | `9,303ms` | `6,604ms` | 무필터 경로 추가 개선 |
| `S2_RELEASE_TYPE_ALBUM` | `8,864ms` | `7,159ms` | 단일값 필터 개선 |
| `S4_GENRE_1` | `12,034ms` | `11,564ms` | 장르 JSON 필터는 미미한 개선 |
| `G2_GENRES_1_7` | `14,089ms` | `13,773ms` | 멀티 장르 필터는 거의 변화 없음 |

- `releaseType`, `year`, `location`처럼 단일값 조건과 정렬 경로는 MySQL 안에서 더 다듬을 수 있었습니다.
- 하지만 장르·디스크립터·언어 같은 JSON 다중값 필터는 인덱스 전략만으로 안정적인 주 경로가 되지 않았습니다.

이 점은 DB 지표에서도 그대로 보였습니다. 같은 `S4_GENRE_1` 기준으로 CH3까지 온 뒤에도

- `Handler_read_key`: `67 -> 62`
- `Handler_read_rnd_next`: `10,001,006 -> 10,001,005`
- `Sort_merge_passes`: `699 -> 650`

수준이어서, 조인 관련 읽기는 이미 줄였지만 JSON 필터와 정렬 비용은 거의 그대로 남아 있었습니다.

이 지점에서 검색 주 경로를 Elasticsearch로 분리했습니다. MySQL 최적화를 적용한 뒤에도 JSON 다중값 필터가 포함된 미스 경로 병목이 남았기 때문입니다.

**이 단계의 결론:** 단일값 필터는 더 빨라졌지만, JSON 다중값 필터는 MySQL만으로 주 경로가 되기 어려웠습니다.

### 3-3. 반복 요청은 Redis가 흡수하고, 비싼 미스 경로는 별도로 확인했습니다

세 번째 단계는 Redis 응답 캐시입니다. 이 단계의 목적은 미스를 감추는 것이 아니라, 반복 요청의 기본 경로와 비싼 미스 경로를 분리하는 것이었습니다.

`cold miss`와 `miss`는 `totalMillis`, `hit`은 `wallMillis` 기준입니다.

| 시나리오 | 최초 미스 | 미스 | 적중 |
| --- | --- | --- | --- |
| `S1_BASELINE` | `6,556ms` | `6,768ms` | `16.85ms` |
| `S4_GENRE_1` | `11,382ms` | `11,386ms` | `16.73ms` |
| `G2_GENRES_1_7` | `13,621ms` | `13,295ms` | `22.98ms` |

- 적중 경로는 15~23ms대로 떨어집니다.
- 하지만 미스 경로는 거의 그대로 남아 있습니다.

즉, Redis는 반복 요청 흡수에는 탁월했지만, MySQL 동적 조회가 비싼 기본 구조 자체를 해결하지는 못했습니다.

**이 단계의 결론:** Redis는 반복 요청을 흡수했지만, 캐시 미스 경로의 핵심 병목은 그대로 남았습니다.

### 3-4. Elasticsearch를 검색 주 경로로 전환하고, MySQL은 fallback으로 남겼습니다

네 번째 단계에서는 Elasticsearch를 검색 주 경로로 전환했습니다. Elasticsearch는 최종 응답을 만드는 대신, 필터 + 정렬 기준으로 릴리즈 ID 후보를 빠르게 찾는 역할만 맡았습니다.

값은 `totalMillis`, `searchMillis`, `hydrateMillis`, `lastUpdatedMillis` 기준입니다.

| 시나리오 | totalMillis | searchMillis | hydrateMillis | lastUpdatedMillis |
| --- | --- | --- | --- | --- |
| `S1_BASELINE` | `4,940ms` | `73ms` | `13ms` | `4,847ms` |
| `S2_RELEASE_TYPE_ALBUM` | `4,906ms` | `52ms` | `12ms` | `4,836ms` |
| `S4_GENRE_1` | `4,790ms` | `37ms` | `13ms` | `4,732ms` |
| `G2_GENRES_1_7` | `5,249ms` | `367ms` | `11ms` | `4,865ms` |

- Elasticsearch 자체는 빨랐습니다.
- 실제 API는 여전히 4,700~5,300ms 수준이었습니다.
- 그리고 그 시간을 잡아먹고 있던 건 거의 전부 `lastUpdatedMillis`였습니다.

갱신 시각은 검색 결과와 무관한 공통 값인데도 당시에는 요청마다 같은 조회 비용이 함께 붙어 있었습니다. 그래서 검색 후보를 아무리 빨리 찾아도, 공통 메타데이터 확인이 끝날 때까지 실제 응답은 계속 묶여 있었습니다. `lastUpdated`는 부가 정보가 아니라 요청마다 4초대 고정 비용으로 붙는 병목이었고, 그래서 `searchMillis`를 수십 ms대로 줄여도 `totalMillis`는 거의 줄지 않았습니다.

검색 후보를 빠르게 찾아도, 메타데이터 경로가 API 전체 시간을 계속 지배하고 있었습니다.

**이 단계의 결론:** Elasticsearch는 검색을 줄였지만, 실제 응답 병목은 메타데이터 경로에 있었습니다.

### 3-5. 마지막 병목은 갱신 시각 조회였고, 그래서 메타데이터 경로를 분리했습니다

마지막 단계는 갱신 시각 조회를 별도 메타데이터 경로로 분리하는 작업이었습니다. 여기서는 엔진을 더 바꾼 것이 아니라, Elasticsearch가 빨라졌는데도 API가 느린 이유를 후처리 경로에서 분리해낸 것입니다.

재측정 구간은 반복 실행 평균이라 `wallAvg`, `totalAvg`, `lastUpdatedAvg`를 함께 적었습니다.

| 시나리오 | 검색 단계 전체 시간 | 당시 갱신 시각 조회 시간 | 재측정 실측 평균 | 재측정 내부 처리 평균 | 재측정 갱신 시각 평균 |
| --- | --- | --- | --- | --- | --- |
| `S2_RELEASE_TYPE_ALBUM` | `4,906ms` | `4,836ms` | `47.45ms` | `16.00ms` | `1.00ms` |
| `S4_GENRE_1` | `4,790ms` | `4,732ms` | `178.37ms` | `145.67ms` | `1.00ms` |
| `G2_GENRES_1_7` | `5,249ms` | `4,865ms` | `206.43ms` | `143.33ms` | `1.33ms` |

이 단계에서야 비로소 Elasticsearch 검색 가속 효과가 실제 API 응답 시간으로 드러났습니다. 검색보다 메타데이터가 더 느린 상태에서는 어떤 검색 최적화도 체감되지 않았고, 갱신 시각 조회를 분리한 뒤에야 미스 경로가 수백 ms대로 내려왔습니다.

**이 단계의 결론:** 메타데이터를 분리한 뒤에야 검색 가속 효과가 실제 응답 시간으로 이어졌습니다.

---

## 4. 핵심 성과

대표값은 아래 여섯 줄입니다. 각 행은 동일 단계 비교가 아니라, 해당 시나리오에서 가장 의미 있는 병목 제거 전후를 묶은 값입니다.

| 항목 | 개선 전 | 개선 후 | 의미 |
| --- | --- | --- | --- |
| 무필터 조회 `S1` | `85,652ms` | `6,604ms` | `chart_scores` 중심 읽기 모델로 기본 조회 경로를 줄였습니다. |
| 단일값 필터 조회 `S2` | `54,488ms` | `7,159ms` | Querydsl과 인덱스 정리로 단일값 필터 경로를 더 줄였습니다. |
| JSON 필터 조회 `S4` | `65,421ms` | `178.37ms` | 검색·메타데이터 경로를 분리한 뒤에야 JSON 필터 미스 구간이 수백 ms대로 내려왔습니다. |
| 반복 요청 응답 `S4 hit` | `11,386ms` | `16.73ms` | Redis 응답 캐시가 반복 요청을 거의 전부 흡수했습니다. |
| 캐시 미스 조회 `G2` | `5,249ms` | `206.43ms` | 복합 JSON 필터 미스도 수백 ms대로 관리 가능한 수준까지 줄였습니다. |
| 메타데이터 조회 | `4,732ms` | `1.00ms` | 갱신 시각을 별도 경로로 분리해 공통 병목을 제거했습니다. |

차트 API는 한 번에 빨라진 것이 아니라, 조인 비용, 검색 비용, 메타데이터 비용을 차례로 분리하며 응답 시간을 줄였습니다.

---

## 5. 현재 서빙 구조

현재 구조는 Elasticsearch가 차트를 직접 서빙하는 형태가 아니라, `chart_scores` 위에 Redis·Elasticsearch·MySQL fallback·메타데이터 경로를 분리한 서빙 계층입니다.

```text
/api/v1/charts?page&size
  -> 요청 조건 기준 캐시 키 계산
  -> Redis 응답 캐시 조회
  -> 캐시 적중: 응답 즉시 반환
  -> 캐시 미스:
       -> Elasticsearch에서 릴리즈 ID 후보 검색
       -> 릴리즈 ID 목록 반환
       -> chart_scores + 릴리즈 조회
       -> 아티스트 조회 + 응답 조립 (갱신 시각, 순위 목록)
       -> Redis 캐시 기록

Redis read/write 실패
  -> 검색/조립 경로로 계속 진행

Elasticsearch 실패
  -> MySQL fallback 조회
  -> 동일한 조립 경로로 응답 반환

메타데이터
  -> 갱신 시각 별도 조회
```

- 차트는 원본 `ratings`를 다시 읽지 않고, 배치가 만든 `chart_scores`를 읽습니다.
- 빠른 기본 경로는 Redis와 Elasticsearch이고, MySQL fallback은 검색 장애나 인덱스 미반영 상황에서도 API를 끊지 않기 위한 운영 안전장치입니다.
- 갱신 시각은 검색 성공 여부와 무관한 공통 조회이므로, 별도 메타데이터 경로로 분리해 부분 실패와 병목을 격리합니다.

---

## 6. 남겨둔 것

- Redis와 Elasticsearch를 도입한 만큼, 다음 과제는 단일 인스턴스 성능보다 분산 운영 관점입니다. 다중 인스턴스 환경에서 캐시 일관성, 검색 인덱스 반영 시점, 장애 전환 전략을 함께 검증해야 합니다.
- MySQL fallback은 API를 살리는 안전망이지만 여전히 비싼 경로입니다. 부분 장애 상황에서 어느 정도의 성능 저하를 허용할지까지 포함해, 고가용성 관점의 운영 기준이 더 필요합니다.
- 차트 최신성은 upstream `release_rating_summary`와 배치 반영 주기에 종속됩니다. 즉시 반영형 API가 아니라 배치 주기에 맞춰 갱신되는 구조입니다.
- 응답 조립 과정에서 릴리즈 조회와 아티스트 조회가 추가로 필요하므로, 검색 외 조회 비용은 계속 관리해야 합니다.

이 구조는 실시간 검색 플랫폼보다, 배치가 만든 차트 결과를 실제 API에서 빠르고 끊기지 않게 읽는 서빙 계층에 가깝습니다. 다음 단계에서는 이 구조를 분산 인스턴스와 고가용성 관점에서 얼마나 안정적으로 운영할 수 있는지까지 확장할 수 있습니다.

---

## 7. 관련 코드

- `src/main/java/com/hipster/chart/controller/ChartController.java`
- `src/main/java/com/hipster/chart/service/ChartService.java`
- `src/main/java/com/hipster/chart/service/ChartSearchService.java`
- `src/main/java/com/hipster/chart/service/ChartResponseAssembler.java`
- `src/main/java/com/hipster/chart/service/ChartCacheKeyGenerator.java`
- `src/main/java/com/hipster/chart/service/ChartLastUpdatedService.java`
- `src/main/java/com/hipster/chart/service/ChartElasticsearchIndexService.java`
- `src/main/java/com/hipster/chart/repository/ChartScoreRepository.java`
- `src/main/java/com/hipster/chart/repository/ChartScoreRepositoryImpl.java`

---

## 8. 관련 문서

- [평점 조회와 차트 배치가 함께 쓰는 공통 집계 계층 만들기](./rating-aggregation.md)
- [차트 배치 재생성과 공개를 분리해 안전한 publish 파이프라인 만들기](./chart-pipeline.md)

---

## 9. 한 줄 요약

**차트 API를 하나의 느린 조회로 다루지 않고, `chart_scores` 기반 읽기 경로를 캐시·검색·fallback·메타데이터 계층으로 분리해 병목과 장애 영향을 경계별로 격리하는 서빙 구조로 재설계했습니다.**

---

## 부록 A. 벤치마크 기준과 측정 환경

| 항목 | 내용 |
| --- | --- |
| 기준 환경 | 로컬 Spring Boot + MySQL + Redis + Elasticsearch 벤치마크 환경 |
| 데이터셋 | `chart_scores` 기준 500만 건 규모 합성 데이터셋 |
| API 기준 | 같은 엔드포인트, 같은 응답 DTO 유지 |
| 기본 응답 조건 | 별도 표기 없으면 `page=0`, `size=20` |
| 대표 지표 | `searchMillis`, `hydrateMillis`, `lastUpdatedMillis`, `assembleMillis`, `totalMillis` |
| 캐시 측정 | Redis 캐시 최초 미스·미스·적중 분리 |
| 지표 해석 | 본문 `지표 읽는 법`의 정의를 따르며, `wallAvg`는 실제 API 체감에 가까운 실측 응답 시간 평균입니다. |
| DB 지표 | `FLUSH STATUS` 이후 `Handler_read%`, `Sort_merge_passes` 수집 |

---

## 부록 B. 전체 시나리오 목록

| 시나리오 | 조건 | 의도 |
| --- | --- | --- |
| `S1_BASELINE` | 필터 없음 | 무필터 기본 경로 |
| `S2_RELEASE_TYPE_ALBUM` | `releaseType=ALBUM` | 단일값 필터 |
| `S3_YEAR_2020` | `releaseYear=2020` | 단일값 필터 |
| `S4_GENRE_1` | `genreIds=[1]` | JSON 다중값 필터 대표 |
| `S5_GENRE_1_RELEASE_TYPE_ALBUM` | `genreIds=[1]`, `releaseType=ALBUM` | JSON + 단일값 혼합 |
| `S6_GENRE_1_RELEASE_TYPE_ALBUM_YEAR_2023` | `genreIds=[1]`, `releaseType=ALBUM`, `year=2023` | 더 좁은 혼합 필터 |
| `J1_GENRE_1_DESCRIPTOR_1` | `genreIds=[1]`, `descriptorId=1` | JSON 필터 2개 조합 |
| `G2_GENRES_1_7` | `genreIds=[1,7]` | 멀티 장르 필터 |

---

## 부록 C. 전체 측정표

### C-1. `chart_scores` 비정규화 전후 전체 표

| 시나리오 | CH1 정규화 조인 | CH2 비정규화 읽기 모델 | 변화 |
| --- | --- | --- | --- |
| `S1_BASELINE` | `85,652ms` | `9,303ms` | 무필터 기본 경로 개선 |
| `S2_RELEASE_TYPE_ALBUM` | `54,488ms` | `8,864ms` | 단일값 필터 개선 |
| `S3_YEAR_2020` | `17,587ms` | `9,236ms` | 단일값 필터 개선 |
| `S4_GENRE_1` | `65,421ms` | `12,034ms` | 장르 필터 개선 |
| `S5_GENRE_1_RELEASE_TYPE_ALBUM` | `50,591ms` | `13,005ms` | 혼합 필터 개선 |
| `S6_GENRE_1_RELEASE_TYPE_ALBUM_YEAR_2023` | `7,154ms` | `12,079ms` | 좁은 필터는 오히려 후퇴 |
| `J1_GENRE_1_DESCRIPTOR_1` | `43,992ms` | `12,859ms` | JSON 필터 2개 조합 개선 |
| `G2_GENRES_1_7` | `61,634ms` | `14,089ms` | 멀티 장르 필터 개선 |

### C-2. MySQL 쿼리/인덱스 최적화 전체 표

| 시나리오 | CH2 비정규화 모델 | CH3 쿼리 재구성 + 인덱스 | 변화 |
| --- | --- | --- | --- |
| `S1_BASELINE` | `9,303ms` | `6,604ms` | 무필터 경로 추가 개선 |
| `S2_RELEASE_TYPE_ALBUM` | `8,864ms` | `7,159ms` | 단일값 필터 개선 |
| `S3_YEAR_2020` | `9,236ms` | `6,875ms` | 단일값 필터 개선 |
| `S4_GENRE_1` | `12,034ms` | `11,564ms` | 장르 JSON 필터는 미미한 개선 |
| `S5_GENRE_1_RELEASE_TYPE_ALBUM` | `13,005ms` | `8,956ms` | 혼합 필터 개선 |
| `S6_GENRE_1_RELEASE_TYPE_ALBUM_YEAR_2023` | `12,079ms` | `8,023ms` | 좁은 혼합 필터 개선 |
| `J1_GENRE_1_DESCRIPTOR_1` | `12,859ms` | `12,540ms` | JSON 필터 2개 조합은 거의 변화 없음 |
| `G2_GENRES_1_7` | `14,089ms` | `13,773ms` | 멀티 장르 필터는 거의 변화 없음 |

### C-3. Redis 응답 캐시 전체 표

| 시나리오 | 최초 미스 | 미스 | 적중 |
| --- | --- | --- | --- |
| `S1_BASELINE` | `6,556ms` | `6,768ms` | `16.85ms` |
| `S2_RELEASE_TYPE_ALBUM` | `6,631ms` | `6,699ms` | `18.88ms` |
| `S3_YEAR_2020` | `6,548ms` | `6,449ms` | `17.98ms` |
| `S4_GENRE_1` | `11,382ms` | `11,386ms` | `16.73ms` |
| `S5_GENRE_1_RELEASE_TYPE_ALBUM` | `8,991ms` | `9,193ms` | `16.44ms` |
| `S6_GENRE_1_RELEASE_TYPE_ALBUM_YEAR_2023` | `8,331ms` | `8,148ms` | `15.69ms` |
| `J1_GENRE_1_DESCRIPTOR_1` | `12,277ms` | `12,330ms` | `17.06ms` |
| `G2_GENRES_1_7` | `13,621ms` | `13,295ms` | `22.98ms` |

### C-4. Elasticsearch 검색 단계 전체 표

| 시나리오 | totalMillis | searchMillis | hydrateMillis | lastUpdatedMillis |
| --- | --- | --- | --- | --- |
| `S1_BASELINE` | `4,940ms` | `73ms` | `13ms` | `4,847ms` |
| `S2_RELEASE_TYPE_ALBUM` | `4,906ms` | `52ms` | `12ms` | `4,836ms` |
| `S3_YEAR_2020` | `4,935ms` | `49ms` | `14ms` | `4,864ms` |
| `S4_GENRE_1` | `4,790ms` | `37ms` | `13ms` | `4,732ms` |
| `S5_GENRE_1_RELEASE_TYPE_ALBUM` | `4,849ms` | `50ms` | `18ms` | `4,773ms` |
| `S6_GENRE_1_RELEASE_TYPE_ALBUM_YEAR_2023` | `5,268ms` | `90ms` | `11ms` | `5,158ms` |
| `J1_GENRE_1_DESCRIPTOR_1` | `5,021ms` | `91ms` | `8ms` | `4,916ms` |
| `G2_GENRES_1_7` | `5,249ms` | `367ms` | `11ms` | `4,865ms` |

### C-5. 메타데이터 분리 후 재측정 표

| 시나리오 | 검색 단계 전체 시간 | 당시 갱신 시각 조회 시간 | 재측정 실측 평균 | 재측정 내부 처리 평균 | 재측정 갱신 시각 평균 |
| --- | --- | --- | --- | --- | --- |
| `S2_RELEASE_TYPE_ALBUM` | `4,906ms` | `4,836ms` | `47.45ms` | `16.00ms` | `1.00ms` |
| `S4_GENRE_1` | `4,790ms` | `4,732ms` | `178.37ms` | `145.67ms` | `1.00ms` |
| `G2_GENRES_1_7` | `5,249ms` | `4,865ms` | `206.43ms` | `143.33ms` | `1.33ms` |
