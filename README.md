# Hipster

사용자 기여 기반 음악 레이팅 시스템 백엔드 프로젝트입니다.  
유저 가중치 재계산, 평점 집계, 차트 서빙, 차트 재생성·공개, 검수 큐, Reward Ledger 흐름을 직접 설계·구현했습니다.

---

## Architecture

![Hipster Architecture](docs/images/architecture-overview.png)

> MySQL을 기준 저장소로 두고, Redis는 차트 캐시, RabbitMQ는 비동기 후속 처리, Elasticsearch는 차트 필터 탐색/조회, Spring Batch와 스케줄러는 재집계·재생성·복구 작업에 활용했습니다.

---

## 핵심 포인트

- 평점 조회 최적화, 차트 배치 입력 분리, 유저 가중치 변경 시 쓰기 증폭 완화를 위해 공통 집계 계층을 도입
- 차트 서빙을 `chart_scores` projection, Redis, Elasticsearch, MySQL fallback, version-aware metadata로 나눠 read-heavy 경로로 안정화
- `AFTER_COMMIT + RabbitMQ` 기반으로 쓰기 경로와 집계 갱신을 분리하고, 전체 재집계 배치로 파생 집계를 복구할 수 있는 구조를 설계
- 차트 재생성과 공개를 `stage snapshot` 기반 생성 단계와 버전 기반 공개 단계로 분리
- 승인·반려 기능을 `claim`, `lease`, `reassign`, `SLA`, 감사 이력, 운영 지표를 갖춘 운영형 moderation queue로 확장
- Reward Ledger를 실제 적립 기록으로 보고 `outbox`, `approvalId` 멱등성, `ledger / reversal` 구조로 유실·중복을 제어

---

## 주요 성과

- 단일 유저 평점 500,000건 기준 통계 계산: **10,420ms → 1,138ms**
- 유저 50,000명 / 평점 5,000,000건 합성 데이터 기준 전체 가중치 재계산 배치: **921,000ms → 359,200ms**
- 단일 릴리즈 평점 10,000건 기준 집계 조회: **806ms → 20ms**
- 동일 릴리즈 100명 동시 평점 등록 기준 쓰기 응답 시간: **126ms → 12.95ms**
- 500만 건 합성 데이터 기준 장르 필터 차트 조회: **65,421ms → 178.37ms**
- 500만 건 규모 합성 데이터 기준 차트 전체 재생성: **11.8분**
- 차트 공개 반영: **296ms**

---

## 상세 문서

- [가중치 재계산 시 쓰기 증폭 줄이기](portfolio/user-credibility-batch.md)
- [평점 조회와 차트 배치가 함께 쓰는 공통 집계 계층 만들기](portfolio/rating-aggregation.md)
- [차트 API 읽기 경로를 캐시·검색·fallback·메타데이터로 분리해 응답 병목 줄이기](portfolio/chart-serving.md)
- [차트 배치 재생성과 공개를 분리해 안전한 publish 파이프라인 만들기](portfolio/chart-pipeline.md)
- [검수 적체와 SLA를 다루는 운영형 moderation queue 만들기](portfolio/moderation-queue.md)
- [승인과 적립 기록의 경계를 분리하고 Reward Ledger를 유실·중복 없이 관리하기](portfolio/reward-ledger.md)
