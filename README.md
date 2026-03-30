# Hipster

사용자 기여를 평점, 차트, 검수, 적립 흐름으로 연결하는 음악 레이팅 시스템 백엔드입니다.  
유저 가중치 재계산, 평점 집계, 차트 서빙·재생성·공개, 검수 큐, Reward Ledger 흐름을 설계하고 구현했습니다.

---

## 핵심 포인트

- `Spring Data JPA + Querydsl + MySQL` 기반으로 원본 평점과 릴리즈별 요약 계층을 분리하고, `AFTER_COMMIT + RabbitMQ + Spring Batch` 전체 재집계 보정으로 평점 집계를 비동기 수렴 구조로 설계
- `Spring Batch` 기반 유저 가중치 재계산이 과거 원본 평점 재기록으로 번지지 않도록 직접 쓰기 범위를 최신 유저 가중치와 통계 계층으로 제한
- 차트 조회를 `Redis` 응답 캐시, `Elasticsearch` 검색, `MySQL` 폴백, 버전 기준 메타데이터 경로로 계층화해 탐색형 read path를 안정화
- 차트 공개를 `generate -> validate -> publish -> serve` 파이프라인과 공개 버전 기준점으로 재구성해 `MySQL`, `Elasticsearch`, `Redis`를 거치는 공개 일관성과 롤백 경계를 분리
- 검수 대기열을 `claim`, `lease`, `reassign`, `SLA`, 감사 이력 구조로 확장하고 `Prometheus + Grafana`로 적체와 `SLA` 초과 상태를 관측 가능한 운영 경로로 전환
- 평점 집계는 결과적 일관성, 적립은 `Outbox + approvalId` 멱등성, 정산은 요청 단위 상태 전이와 조정 기록으로 설계해 도메인별 정합성 요구사항을 다르게 적용

---

## 주요 성과

- 단일 유저 평점 500,000건 기준 통계 계산을 **10,420ms -> 1,138ms**, 유저 50,000명 / 평점 5,000,000건 합성 데이터 기준 전체 가중치 재계산 배치를 **921,000ms -> 359,200ms**로 개선
- 단일 릴리즈 평점 10,000건 기준 집계 조회를 **806ms -> 20ms**, 동일 릴리즈 100건 동시 평점 등록 평균 응답을 **126ms -> 12.95ms**로 단축
- 500만 건 합성 데이터 기준 장르 필터 차트 조회를 **65,421ms -> 178.37ms**, 복합 장르 필터 조회를 **5,249ms -> 206.43ms**, 반복 요청 캐시 적중 경로를 **11,386ms -> 16.73ms**로 개선
- 차트 공개 이후 Redis 공개 버전, Elasticsearch 별칭, API 응답이 같은 공개 버전을 가리키도록 검증했고, `publishedAt -> apiVisibleAt` 간격 **296.03ms**를 확인
- 검수 적체를 `SLA`와 운영 이력 기반 대기열로 전환하고 `Prometheus` 메트릭으로 대기 수, 검수 중 수, `SLA` 초과 수를 추적 가능하게 구성
- 적립은 중복·차단·취소 사유를 원장 기록으로 남기고, 정산은 미확정·성공·실패·조정 상태를 분리해 늦은 실패까지 설명 가능한 흐름으로 정리

---

## 상세 문서

- [유저 가중치 변경이 만드는 쓰기 증폭을 줄이기 위한 구조 재설계](portfolio/user-credibility-batch.md)
- [평점 조회와 차트 배치가 함께 쓰는 공통 집계 계층 만들기](portfolio/rating-aggregation.md)
- [차트 공개 일관성을 위해 publish 파이프라인 다시 세우기](portfolio/chart-pipeline.md)
- [차트 API 읽기 경로를 캐시·검색·fallback·메타데이터로 분리해 응답 병목 줄이기](portfolio/chart-serving.md)
- [검수 적체와 SLA를 다루는 운영형 moderation queue 만들기](portfolio/moderation-queue.md)
- [승인과 적립 기록의 경계를 분리하고 Reward Ledger를 유실·중복 없이 관리하기](portfolio/reward-ledger.md)
- [적립 이후 실제 지급까지 설명 가능한 정산 흐름 만들기](portfolio/settlement-pay-and-reconcile.md)