# Hipster

> 사용자 평점을 집계해 차트를 제공하고, 사용자 기여는 검수와 보상 흐름으로 관리하는 음악 레이팅 플랫폼 백엔드입니다. 조회 성능이 중요한 영역은 집계·캐시·검색 경로로 분리하고, 보상과 정산은 상태를 끝까지 설명할 수 있는 구조로 설계했습니다.

## 🛠 Tech Stack

Java 17 · Spring Boot 3.2.3 · Spring Data JPA · Querydsl · Spring Batch · MySQL · Redis · Elasticsearch · RabbitMQ · Prometheus · Grafana · Docker

## 📐 Architecture Overview

```mermaid
flowchart LR
    U[사용자]
    DB[(MySQL)]
    MQ[RabbitMQ]
    CACHE[(Redis)]
    ES[(Elasticsearch)]
    PAY[지급 게이트웨이]

    subgraph SYS[Hipster 시스템]
        API[API 서버]
        CAT[카탈로그·검수]
        RATE[평점]
        SUM[평점 집계]
        CHART[차트 배치·서빙]
        MONEY[보상·정산]
    end

    U --> API

    API --> CAT
    API --> RATE
    API --> CHART
    API --> MONEY

    CAT --> DB
    RATE --> DB
    SUM --> DB
    CHART --> DB
    MONEY --> DB

    RATE -.-> MQ
    MQ -.-> SUM
    SUM -.-> CHART

    CHART --> CACHE
    CHART --> ES
    MONEY --> PAY

    classDef process fill:#eaf4ff,stroke:#5b8def,color:#1f2937,stroke-width:1.2px;
    classDef batch fill:#fff4cc,stroke:#d4a017,color:#1f2937,stroke-width:1.2px;
    classDef store fill:#f3f4f6,stroke:#9ca3af,color:#1f2937,stroke-width:1.2px;
    classDef external fill:#e8f7e8,stroke:#58a55c,color:#1f2937,stroke-width:1.2px;

    class API,CAT,RATE,SUM,CHART,MONEY process;
    class MQ batch;
    class DB,CACHE,ES store;
    class U,PAY external;
```

## ⚡ Key Achievements

### 조회 / 응답 성능

모든 수치는 로컬 환경 기준입니다.  

| 항목 | 데이터 규모 · 조건 | Before | After |
|---|---|---:|---:|
| 차트 조회 | 500만 건 합성 데이터 · 장르 조건 조회 | 65,421ms | 178.37ms |
| 차트 조회 | 500만 건 합성 데이터 · 반복 요청 적중 경로 | 11,386ms | 16.73ms |
| 평점 집계 조회 | 동일 릴리즈 1건 · 유저 10,000명 · 평점 10,000건 | 806ms | 20ms |
| 평점 등록 응답 | 동일 릴리즈 100건 동시 등록 평균 | 126ms | 12.95ms |

### 배치 / 처리 성능

모든 수치는 로컬 환경 기준입니다.  

| 항목 | 데이터 규모 · 조건 | Before | After |
|---|---|---:|---:|
| 차트 재생성 배치 | 500만 건 집계 기준 환산 | 약 87.9분 | 약 23.9분 |
| 유저 가중치 배치 | 유저 50,000명 · 평점 5,000,000건 합성 데이터 | 921,000ms | 359,200ms |

### 정합성 / 운영 설계

- **차트 공개 파이프라인 분리**: 생성, 검증, 공개, 서빙을 나눠 Redis 공개 버전, 갱신 시각, Elasticsearch alias, API 응답이 같은 공개 기준을 따르도록 만들었습니다.
- **검수 대기열 운영화**: 담당 전환, 점유 회수, SLA 기준을 응답과 메트릭에 함께 노출해 backlog를 시스템 안에서 읽을 수 있게 만들었습니다.
- **적립 원장 분리**: 승인과 적립을 분리하고, 중복 적립, 정책 차단, 취소를 원장 기록으로 설명할 수 있게 만들었습니다.
- **정산 상태 모델링**: 총 적립 잔액과 정산 가능 금액을 분리하고, 타임아웃과 늦은 실패를 미확정과 조정 기록으로 추적할 수 있게 만들었습니다.

## 📚 Documents

| 문서 | 핵심 주제 |
|---|---|
| [유저 가중치 변경이 만드는 쓰기 증폭을 줄이기 위한 구조 재설계](./portfolio/user-credibility-batch.md) | Spring Batch / write path / 유저 가중치 |
| [평점 집계 계층을 분리하고 결과적 일관성으로 수렴시키기](./portfolio/rating-aggregation.md) | rating summary / RabbitMQ / Anti-Entropy |
| [차트 재생성 배치 비용을 줄이기](./portfolio/chart-batch-performance.md) | chart batch / stage write / ES source fetch |
| [차트 생성·검증·공개를 분리해 공개 지표 신뢰도 지키기](./portfolio/chart-pipeline.md) | publish pipeline / 공개 버전 / rollback |
| [차트 API 조회 경로를 캐시·검색·폴백·메타데이터로 분리해 응답 병목 줄이기](./portfolio/chart-serving.md) | Redis / Elasticsearch / MySQL fallback |
| [검수 적체와 담당 전환을 현재 상태·운영 이력·SLA로 관리하는 검수 대기열](./portfolio/moderation-queue.md) | moderation queue / backlog / SLA |
| [승인과 적립을 분리해 보상 상태를 설명하는 적립 원장](./portfolio/reward-ledger.md) | outbox / ledger / idempotency |
| [외부 지급을 설명 가능한 상태로 다루는 정산 모델](./portfolio/settlement-pay-and-reconcile.md) | payout / reconcile / state transition |

## 🔍 Flow Diagrams

### 평점 흐름

```mermaid
flowchart LR
    U[사용자]
    API[평점 등록·수정·삭제]
    RAW[평점 원본 저장]
    EVT[평점 이벤트 생성]
    MQ[RabbitMQ]
    SUM[평점 집계 갱신]
    ACT[유저 활동 갱신]
    FIX[전체 재집계 보정]

    U --> API
    API --> RAW
    RAW --> EVT
    EVT -.-> MQ

    MQ -.-> SUM
    MQ -.-> ACT
    FIX -.-> SUM

    classDef process fill:#eaf4ff,stroke:#5b8def,color:#1f2937,stroke-width:1.2px;
    classDef batch fill:#fff4cc,stroke:#d4a017,color:#1f2937,stroke-width:1.2px;
    classDef external fill:#e8f7e8,stroke:#58a55c,color:#1f2937,stroke-width:1.2px;
    classDef recovery fill:#fdecec,stroke:#d96b6b,color:#1f2937,stroke-width:1.2px;

    class API,RAW,EVT,SUM,ACT process;
    class MQ batch;
    class U external;
    class FIX recovery;
```

### 차트 흐름

#### 차트 배치·공개

```mermaid
flowchart LR
    RS[평점 집계]
    BATCH[차트 배치 계산]
    CAND[후보 버전 준비]
    CHECK[검증]
    PUBLISH[공개 전환]
    MYSQL[(MySQL 공개 차트 데이터)]
    ES[(Elasticsearch 공개 인덱스)]
    REDIS[(Redis 메타·캐시)]
    STOP[공개 중단]
    ROLLBACK[이전 버전 복구]

    RS --> BATCH --> CAND --> CHECK
    CHECK -->|검증 성공| PUBLISH
    CHECK -.->|검증 실패| STOP

    PUBLISH --> MYSQL
    PUBLISH --> ES
    PUBLISH --> REDIS
    PUBLISH -.->|공개 전환 실패 시| ROLLBACK

    classDef process fill:#eaf4ff,stroke:#5b8def,color:#1f2937,stroke-width:1.2px;
    classDef batch fill:#fff4cc,stroke:#d4a017,color:#1f2937,stroke-width:1.2px;
    classDef store fill:#f3f4f6,stroke:#9ca3af,color:#1f2937,stroke-width:1.2px;
    classDef recovery fill:#fdecec,stroke:#d96b6b,color:#1f2937,stroke-width:1.2px;

    class RS,CHECK,PUBLISH process;
    class BATCH,CAND batch;
    class MYSQL,ES,REDIS store;
    class STOP,ROLLBACK recovery;
```

#### 차트 조회·서빙

```mermaid
flowchart LR
    USER[사용자]
    API[차트 조회 API]
    REDIS[(Redis 응답 캐시)]
    ES[(Elasticsearch 검색)]
    MYSQL[(MySQL 공개 차트 데이터)]
    RESP[차트 응답]

    USER --> API --> REDIS
    REDIS -->|cache hit| RESP
    REDIS -->|cache miss| ES
    ES -->|검색 결과 기준 조회| MYSQL
    ES -.->|검색 실패 시 fallback| MYSQL
    MYSQL --> RESP

    classDef process fill:#eaf4ff,stroke:#5b8def,color:#1f2937,stroke-width:1.2px;
    classDef store fill:#f3f4f6,stroke:#9ca3af,color:#1f2937,stroke-width:1.2px;
    classDef external fill:#e8f7e8,stroke:#58a55c,color:#1f2937,stroke-width:1.2px;

    class API,RESP process;
    class REDIS,ES,MYSQL store;
    class USER external;
```

### 승인 · 적립 · 정산 흐름

#### 검수·승인·적립

```mermaid
flowchart LR
    CAT[카탈로그 등록 요청]
    REV[리뷰 게시 요청]
    QUEUE[검수 대기열]
    CLAIM[검수 항목 점유]
    REVIEW[검수 처리]
    EXPIRE[점유 만료 복구]
    APPROVE[승인]
    REJECT[반려]
    ROUTBOX[적립 outbox]
    RMQ[RabbitMQ]
    LEDGER[적립 원장]
    RETRY[적립 재시도]

    CAT --> QUEUE
    REV --> QUEUE
    QUEUE --> CLAIM --> REVIEW

    EXPIRE -.-> QUEUE

    REVIEW -->|승인| APPROVE
    REVIEW -->|반려| REJECT

    APPROVE --> ROUTBOX
    ROUTBOX -.-> RMQ
    RMQ -.-> LEDGER
    RETRY -.-> ROUTBOX

    classDef process fill:#eaf4ff,stroke:#5b8def,color:#1f2937,stroke-width:1.2px;
    classDef batch fill:#fff4cc,stroke:#d4a017,color:#1f2937,stroke-width:1.2px;
    classDef recovery fill:#fdecec,stroke:#d96b6b,color:#1f2937,stroke-width:1.2px;

    class CAT,REV,QUEUE,CLAIM,REVIEW,APPROVE,REJECT,LEDGER process;
    class ROUTBOX,RMQ batch;
    class EXPIRE,RETRY recovery;
```

#### 정산 요청·지급·보정

```mermaid
flowchart LR
    LEDGER[적립 원장]
    BAL[정산 가능 금액 계산]
    REQ[정산 요청]
    SOUTBOX[지급 outbox]
    RETRY[지급 재시도]
    PAY[지급 게이트웨이]
    WEB[웹훅·대사]
    DONE[정산 완료]
    FAIL[실패 처리·예약 해제]
    ADJ[정산 조정]

    LEDGER --> BAL --> REQ --> SOUTBOX
    RETRY -.-> SOUTBOX
    SOUTBOX -.-> PAY

    PAY -->|성공| DONE
    PAY -->|실패| FAIL
    PAY -->|미확정| WEB

    WEB -->|성공 확정| DONE
    WEB -->|실패 확정| FAIL
    WEB -->|사후 실패| ADJ

    ADJ --> BAL

    classDef process fill:#eaf4ff,stroke:#5b8def,color:#1f2937,stroke-width:1.2px;
    classDef batch fill:#fff4cc,stroke:#d4a017,color:#1f2937,stroke-width:1.2px;
    classDef external fill:#e8f7e8,stroke:#58a55c,color:#1f2937,stroke-width:1.2px;
    classDef recovery fill:#fdecec,stroke:#d96b6b,color:#1f2937,stroke-width:1.2px;

    class LEDGER,BAL,REQ,DONE process;
    class SOUTBOX,WEB batch;
    class PAY external;
    class RETRY,FAIL,ADJ recovery;
```

#### 정산 상태 전이

```mermaid
stateDiagram-v2
    state "요청 생성" as REQUESTED
    state "예약 완료" as RESERVED
    state "지급 요청 전송" as SENT
    state "지급 미확정" as UNKNOWN
    state "정산 완료" as SUCCEEDED
    state "정산 실패" as FAILED
    state "조정 필요" as NEEDS_ADJUSTMENT

    [*] --> REQUESTED
    REQUESTED --> RESERVED

    RESERVED --> SENT
    RESERVED --> UNKNOWN
    RESERVED --> FAILED

    SENT --> SUCCEEDED
    SENT --> UNKNOWN
    SENT --> FAILED

    UNKNOWN --> SUCCEEDED
    UNKNOWN --> FAILED

    SUCCEEDED --> NEEDS_ADJUSTMENT
```
