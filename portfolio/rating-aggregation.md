# 평점 조회와 차트 배치가 함께 쓰는 공통 집계 계층 만들기

> `release_rating_summary`는 단순 조회 캐시가 아닙니다.  
> 이 테이블은 원본 `ratings`와 downstream 차트 사이에서, 평점 조회 성능, 짧은 write path, 유저 가중치 변경 흡수, 전체 재집계 기반 복구 가능성까지 함께 맡는 공통 집계 계층입니다.

이 문서에서 보여드리고 싶은 핵심은 RabbitMQ 도입 자체가 아닙니다.  
핵심은 `release_rating_summary`를 단순 조회 캐시가 아니라, 평점 조회와 차트 배치가 함께 쓰는 공통 집계 계층으로 승격한 것입니다.

---

## 1. 문제 상황

초기 구조에서는 릴리즈 상세와 목록에서 평균 평점과 평점 수를 보여주기 위해 원본 `ratings`를 직접 읽고 집계했습니다.  
이 방식은 평점이 쌓일수록 조회 비용이 선형으로 증가했고, 조회 핫패스가 원본 테이블 스캔에 계속 묶여 있었습니다.

더 큰 문제는 이 읽기 비용을 줄이려는 과정에서, 유저 가중치가 반영된 값을 평점 row 쪽에 미리 들고 있으려는 발상이 쉽게 열렸다는 점입니다.  
`ratings.weighted_score` 같은 비정규화 컬럼 전제를 유지하면, 조회 문제를 줄이는 대신 쓰기 문제를 더 크게 열게 됩니다.

이 전제를 유지하는 한, 유저 가중치 변경은 아래 비용으로 전파될 수밖에 없었습니다.

- 유저 가중치 1회 변경
- 해당 유저의 과거 `rating` row 다수 재기록
- 대규모 UPDATE에 따른 쓰기 비용과 락 부담 증가
- 이후 집계와 차트 계산 비용까지 함께 악화

즉, 원본 직접 조회 구조는 읽기 비용을 키웠고, 이를 per-rating 비정규화로 덮으려는 시도는 write amplification을 열기 쉬웠습니다.  
결국 조회 문제와 쓰기 문제를 동시에 끊으려면, 원본 평점과 downstream 차트 사이에 별도의 공통 집계 계층이 필요했습니다.

---

## 2. 구조적 판단

제가 내린 핵심 판단은 두 가지였습니다.

첫째, source of truth는 끝까지 `ratings`로 유지해야 했습니다.  
둘째, `release_rating_summary`는 조회 최적화용 보조 테이블이 아니라, 원본 평점과 차트 사이에서 집계 책임을 흡수하는 공통 계층으로 설계해야 했습니다.

구조는 아래처럼 다시 나눴습니다.

- `ratings`
  - 원본 평점과 변경 이력을 보존하는 source of truth입니다.
- `release_rating_summary`
  - 조회 경로와 차트 배치가 함께 읽는 공통 집계 계층입니다.
- `chart_scores`
  - 최종 차트 서빙을 위한 downstream 결과 계층입니다.

`release_rating_summary`는 단순 평균 캐시가 아니라 아래 역할을 함께 맡습니다.

- `total_rating_count`, `average_score`
  - 릴리즈 상세와 목록 조회가 직접 참조하는 읽기 모델입니다.
- `weighted_score_sum`, `weighted_count_sum`
  - 차트 배치가 weighted average와 Bayesian score를 계산할 때 읽는 upstream 집계값입니다.
- `updated_at`, `batch_synced_at`
  - 실시간 증분 반영과 전체 재집계가 충돌하지 않도록 경계를 잡는 운영 메타데이터입니다.

이 구조의 핵심은, 조회 성능과 파생 집계 책임을 `release_rating_summary` 한 계층으로 모았다는 점입니다.  
그래야 유저 가중치 변화도 과거 평점 row 재기록이 아니라, 이후 집계 경로가 최신 `users.weighting_score`를 읽어 summary를 다시 계산하는 방식으로 흡수할 수 있습니다.

또한 평점 집계와 차트 반영은 결제나 재고처럼 즉시 강일관성이 필요한 흐름이 아니라고 판단했습니다.  
현재 규모에서는 강한 동기 일관성보다 짧은 write path와 원본 기준 전체 재집계가 가능한 복구 구조가 더 중요했습니다.

---

## 3. 해결 과정

### 3-1. `release_rating_summary`로 조회 핫패스를 분리했지만, 동기 write 병목이 드러났습니다

가장 먼저 한 일은 릴리즈별 집계값을 `release_rating_summary`로 분리하고, 조회 경로가 원본 `ratings` 대신 이 계층을 읽도록 바꾸는 것이었습니다.

이 전환으로 릴리즈 상세와 목록에서 평균 평점과 평점 수를 가져오는 비용은 크게 줄었습니다.  
하지만 처음에는 평점 생성/수정/삭제 트랜잭션 안에서 summary까지 동기 갱신했기 때문에, 같은 릴리즈에 쓰기가 몰릴수록 summary row가 새로운 경합 지점이 됐습니다.

즉, 조회 핫패스는 빨라졌지만 아래 문제가 드러났습니다.

- 원본 평점 저장과 파생 집계 갱신이 같은 트랜잭션에 묶였습니다.
- summary row에 대한 경쟁이 API 응답 시간으로 직접 전파됐습니다.
- 읽기 문제를 푼 대신 write path가 길어지는 병목이 생겼습니다.

여기서 얻은 결론은 분명했습니다.  
`release_rating_summary`는 필요했지만, 이 계층을 원본 쓰기와 동기 결합한 채로는 운영하기 어려웠습니다.

### 3-2. 그래서 write path와 집계 갱신을 `AFTER_COMMIT + RabbitMQ`로 분리했습니다

다음 판단은 원본 쓰기와 파생 집계 갱신을 같은 트랜잭션 안에서 끝내지 않는 것이었습니다.

현재 구현은 아래 흐름으로 동작합니다.

1. `RatingService`가 원본 `ratings`를 저장하거나 수정합니다.
2. 같은 트랜잭션 안에서 즉시 summary를 건드리지 않고, 평점 생성/수정/삭제 이벤트만 발행합니다.
3. `RatingMessageProducer`가 `@TransactionalEventListener(phase = AFTER_COMMIT)`로 커밋 이후에만 RabbitMQ로 메시지를 보냅니다.
4. `RatingSummaryConsumer`가 release 단위 delta를 받아 `RatingSummaryService`를 통해 `release_rating_summary`를 증분 갱신합니다.

이렇게 바꾸면 원본 write path는 `ratings` 반영까지만 책임지고, `release_rating_summary` 갱신은 커밋 이후 비동기 작업으로 밀려납니다.  
RabbitMQ는 여기서 주인공이 아니라, 공통 집계 계층을 짧은 write path와 함께 운영하기 위한 분리 장치입니다.

운영 측면에서는 아래 장치도 함께 들어갔습니다.

- summary 소비자는 manual ack로 성공/실패를 명시적으로 처리합니다.
- 영구 실패는 DLQ로 분리해 write path 전체를 오염시키지 않게 했습니다.
- 메시지는 rating 이벤트 단위로 오지만, 반영은 결국 release 단위 summary 갱신으로 수렴합니다.

### 3-3. 비동기 증분 갱신만으로 끝내지 않고, Anti-Entropy 전체 보정 경로를 닫았습니다

비동기 증분 반영만으로는 `release_rating_summary`를 최종 정답으로 둘 수 없습니다.  
현재 구조는 RabbitMQ At-Least-Once 전달을 전제로 하므로, 중복 전달, 지연 재전달, 소비 실패 재시도 같은 요인으로 summary가 일시적으로 오염될 수 있습니다.

그래서 `release_rating_summary`는 실시간 증분 반영만 있는 테이블이 아니라, 원본 기준 전체 재집계로 언제든 다시 만들 수 있는 파생 집계 계층으로 설계했습니다.

현재 전체 보정 경로는 이렇게 동작합니다.

- `AntiEntropyBatchJob`이 release id 전체를 청크로 나눠 순회합니다.
- `AntiEntropyQueryRepository`가 `ratings JOIN users`를 source of truth로 읽어 `release_rating_summary`를 다시 계산합니다.
- 마지막 평점이 삭제된 릴리즈까지 정리할 수 있도록, `ratings`에 남은 release id와 기존 summary의 release id를 함께 수집해 stale row 삭제까지 처리합니다.
- 전체 재집계는 `total_rating_count`, `average_score`, `weighted_score_sum`, `weighted_count_sum`, `batch_synced_at`를 한 번에 덮어씁니다.

중요한 점은 이 배치가 summary를 다시 읽어 스스로 고치는 것이 아니라, 끝까지 원본 `ratings`와 최신 `users.weighting_score`를 기준으로 summary를 재구성한다는 점입니다.  
이 때문에 메시지 중복이나 누락으로 증분 반영이 틀어져도, 원본 기준 전체 재집계로 `release_rating_summary`를 다시 맞출 수 있습니다.

또한 증분 경로와 전체 재집계 경로가 서로 덮어쓰지 않도록 `batch_synced_at` 경계도 두었습니다.  
실시간 증분 native query는 `event_ts > batch_synced_at` 조건에서만 반영되므로, 전체 재집계 직후 늦게 도착한 오래된 메시지가 summary를 다시 오염시키지 않게 막습니다.

### 3-4. 유저 가중치 변경도 과거 평점 row 재기록 없이 이 계층이 흡수하게 만들었습니다

이 구조가 중요한 이유는 조회 성능 때문만이 아닙니다.  
유저 가중치 변경을 과거 평점 row 재기록 없이 흡수하게 만드는 핵심 계층도 `release_rating_summary`이기 때문입니다.

현재 유저 가중치 배치는 `users.weighting_score`와 `user_weight_stats`만 갱신합니다.  
그 다음 반영은 과거 `ratings` row를 다시 쓰는 방식이 아니라, 이후 평점 집계 경로가 최신 `users.weighting_score`를 읽어 `release_rating_summary`를 다시 계산하는 방식으로 흡수합니다.

즉, 가중치 변경의 전파 경로는 아래처럼 바뀌었습니다.

- 이전
  - 유저 가중치 변경 -> 과거 `ratings` row 재기록 -> downstream 재집계
- 현재
  - 유저 가중치 변경 -> `users.weighting_score` 갱신 -> `ratings JOIN users.weighting_score` 기반 summary 재계산 -> chart batch 반영

이렇게 바꾸면서 느리게 바뀌는 정책 값을 과거 평점 row 재기록에 연결하던 구조적 전제를 끊었습니다.  
그 결과 차트 배치도 더 이상 raw `ratings`를 직접 뒤지거나, per-rating 비정규화 값에 기대지 않고 `release_rating_summary`라는 안정적인 upstream 계층을 읽게 되었습니다.

---

## 4. 핵심 성과

| 시나리오 | 측정값 | 의미 |
| --- | --- | --- |
| 원본 `ratings` 직접 조회 | **806ms** | 릴리즈 상세/목록 조회가 원본 집계 비용에 직접 묶여 있던 baseline입니다. |
| `release_rating_summary` 조회 전환 | **20ms** | 조회 핫패스를 공통 집계 계층으로 분리해 읽기 성능을 안정화했습니다. |
| summary 동기 갱신 결합 write | **7ms -> 126ms** | 조회는 빨라졌지만, 파생 집계 동기 갱신으로 write hotspot이 노출됐습니다. |
| `AFTER_COMMIT + RabbitMQ` 분리 write | **126ms -> 12.95ms** | 원본 write path와 summary 갱신을 분리해 API 응답 시간을 다시 줄였습니다. |
| 동일 부하 기준 총 처리 시간 | **12,600ms -> 1,295ms** | 동기 결합을 끊고 공통 집계 계층 중심으로 재구성한 최종 효과입니다. |

아래 수치는 로컬 포트폴리오 환경에서 단계별로 측정한 값입니다.  
중요한 것은 절대값보다, 병목이 어디서 드러났고 어떤 구조 변경으로 해소됐는지가 단계별로 보인다는 점입니다.

이 문서의 성과는 단순한 조회 속도 개선이 아닙니다.

- 원본 직접 조회와 per-rating 비정규화 사이에서 흔들리던 책임을 `release_rating_summary`로 재배치했습니다.
- read path와 write path를 분리해 조회 최적화가 쓰기 병목으로 되돌아오지 않게 만들었습니다.
- 차트 배치가 raw 테이블 대신 공통 집계 계층을 읽는 구조를 만들었습니다.
- 실시간 증분 반영이 틀어져도 원본 기준 전체 재집계로 다시 맞출 수 있는 복구 경로를 확보했습니다.

---

## 5. 현재 구조와 책임

이 구조의 핵심은 `release_rating_summary`를 조회 최적화 테이블이 아니라, 원본과 차트 사이에 놓인 공통 집계 계층으로 취급했다는 점입니다.

```text
ratings
  -> AFTER_COMMIT rating event
  -> RabbitMQ
  -> release_rating_summary incremental update

ratings + users.weighting_score
  -> Anti-Entropy full rebuild
  -> release_rating_summary overwrite / correction

release_rating_summary
  -> release detail/list/search read path
  -> chart batch upstream
```

현재 각 계층의 책임은 아래처럼 정리됩니다.

- `ratings`
  - 원본 평점과 변경 이력을 보존하는 기준면입니다.
- `release_rating_summary`
  - 조회용 평균/건수와 차트용 weighted sum/count를 함께 들고 있는 공통 집계 계층입니다.
- 차트 배치
  - `release_rating_summary`의 `weighted_score_sum`, `weighted_count_sum`을 읽어 global average와 Bayesian score를 계산합니다.

즉, `release_rating_summary`는 단순 캐시가 아니라, 원본 평점과 downstream 차트 사이에서 읽기 모델과 파생 집계 기준면을 동시에 맡는 구조입니다.

---

## 6. 최종적으로 얻은 것

- 릴리즈 상세와 목록 조회가 raw `ratings` 집계 비용에 직접 묶이지 않게 만들었습니다.
- 유저 가중치 변경을 과거 평점 row 재기록 없이 `release_rating_summary` 재계산으로 흡수하게 만들었습니다.
- 원본 write path를 짧게 유지하면서도, 파생 집계 반영을 운영 가능한 수준으로 분리했습니다.
- 차트 배치가 더 안정적인 upstream 집계 계층을 읽도록 만들었습니다.
- 실시간 증분 반영이 틀어져도 원본 기준 전체 재집계로 summary를 다시 만들 수 있는 복구 가능성을 확보했습니다.

결국 이 작업의 본질은 summary 테이블 하나를 추가한 것이 아니라, 원본 평점과 downstream 차트 사이의 집계 책임을 `release_rating_summary`로 승격해 다시 나눈 것입니다.

---

## 7. 남겨둔 것

- `release_rating_summary`는 강한 실시간 일관성을 보장하지 않습니다.
- RabbitMQ는 At-Least-Once 전달이므로, 중복/지연 반영 가능성 자체가 완전히 사라지지는 않습니다.
- Outbox까지 도입한 구조는 아니므로, 메시지 전달 보장은 현재 수준에서 운영 장치와 Anti-Entropy에 의존합니다.
- 전체 재집계 배치를 계속 운영해야 하므로, 파생 집계 계층을 굴리는 비용이 추가됩니다.

하지만 이 비용은 의식적으로 받아들인 것입니다.  
평점 집계와 차트 반영은 결제나 재고처럼 즉시 강일관성이 필요한 흐름이 아니라고 판단해, 현재 규모에서는 짧은 write path와 원본 기준 복구 가능성을 더 우선했습니다.

---

## 8. 관련 코드

아래 코드는 이 문서의 핵심 흐름을 실제로 구현한 파일들입니다.

- `src/main/java/com/hipster/rating/service/RatingService.java`
- `src/main/java/com/hipster/rating/event/RatingMessageProducer.java`
- `src/main/java/com/hipster/rating/event/RatingSummaryConsumer.java`
- `src/main/java/com/hipster/global/config/RabbitMqConfig.java`
- `src/main/java/com/hipster/rating/service/RatingSummaryService.java`
- `src/main/java/com/hipster/rating/domain/ReleaseRatingSummary.java`
- `src/main/java/com/hipster/rating/repository/ReleaseRatingSummaryRepository.java`
- `src/main/java/com/hipster/release/service/ReleaseService.java`
- `src/main/java/com/hipster/batch/antientropy/AntiEntropyBatchJob.java`
- `src/main/java/com/hipster/batch/antientropy/AntiEntropyQueryRepository.java`
- `src/main/java/com/hipster/batch/chart/config/ChartJobConfig.java`
- `src/main/java/com/hipster/batch/chart/step/ChartItemReaderConfig.java`
- `src/main/java/com/hipster/batch/chart/step/ChartItemProcessor.java`

---

## 9. 관련 문서

아래 문서는 이 공통 집계 계층이 유저 가중치 변경과 차트 publish 흐름으로 어떻게 이어지는지 함께 보여주는 공개 포트폴리오 문서입니다.

- [유저 가중치 재계산 배치에서 쓰기 증폭 줄이기](./user-credibility-batch.md)
- [차트 배치 재생성과 공개를 분리해 안전한 publish 파이프라인 만들기](./chart-pipeline.md)

---

## 10. 한 줄 요약

`release_rating_summary`를 단순 조회 캐시가 아니라, 평점 조회와 차트 배치가 함께 쓰는 공통 집계 계층으로 승격해 조회 성능, 짧은 write path, 유저 가중치 흡수, 복구 가능한 파생 집계 책임을 함께 정리했습니다.
