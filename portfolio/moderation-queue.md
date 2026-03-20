# 검수 적체와 SLA를 다루는 운영형 moderation queue 만들기

> moderation을 단순 요청 저장소나 검수 기능 집합으로 두지 않고, `lease`, `timeout recovery`, `reassign`, `audit trail`, `SLA`, 운영 메트릭을 갖춘 운영형 queue로 승격했습니다.  
> 핵심은 검수 기능을 더 붙인 것이 아니라, backlog의 현재 상태, 점유 회수, 담당 전환, 운영 이력, SLA 관측을 책임별로 분리해 사람이 처리하는 backlog를 시스템적으로 통제 가능하게 만든 것입니다.

이 문서에서 보여드리고 싶은 핵심은 “claim, reassign, audit를 몇 개 더 붙였다”가 아닙니다.  
문제의 본질이 검수 기능 부족보다 **운영 비용이 사람에게 과하게 전가된 구조**였고, 이를 **backlog를 설명 가능하게 통제하는 운영형 moderation queue**로 바꾼 과정입니다.

읽는 사람이 마지막에 한 문장으로 기억했으면 하는 내용도 이것입니다.

> moderation을 단순 요청 저장소나 검수 기능 집합이 아니라, `lease`, `timeout recovery`, `reassign`, `audit trail`, `SLA`, 운영 메트릭을 갖춘 운영형 queue로 승격해 backlog를 설명 가능하게 통제하는 구조를 만들었다.

---

## 1. 문제 상황

초기 moderation은 제출 저장과 기본 `claim`, `approve`, `reject` 흐름까지는 갖추고 있었습니다.  
`ModerationQueue`에도 이미 `moderatorId`, `claimedAt`, `claimExpiresAt`가 있었고, 운영자가 검수 항목을 점유하는 최소 모델도 있었습니다.

하지만 운영 관점에서 보면 이 구조는 queue라기보다 **검수 기능이 붙은 요청 저장소**에 가까웠습니다.

- `lease` 필드는 있었지만, 만료 회수는 독립된 운영 메커니즘이 아니라 요청 유입에 기대고 있었습니다.
- 공식 `reassign` 경로가 없어 `unclaim -> 다른 운영자 claim` 같은 우회 절차가 필요했습니다.
- 최신 상태는 보여도, 누가 언제 점유했고 언제 풀렸고 누가 다시 잡았는지는 별도 이력으로 남지 않았습니다.
- backlog와 `SLA` 초과 상태를 queue 응답과 메트릭에서 바로 읽을 수 없었습니다.
- 결국 검수 backlog 운영 비용이 시스템보다 사람의 기억, 메신저, 수동 조율에 더 많이 기대고 있었습니다.

특히 문제의 본질은 “몇 가지 API가 없었다”가 아니었습니다.  
**시스템이 운영 책임을 거의 지지 않고 있었다**는 점이 더 큰 문제였습니다.

- 방치된 `UNDER_REVIEW` 항목을 시스템이 요청 유입 없이 회수하지 못했습니다.
- backlog가 얼마나 밀렸는지, 지금 누가 무엇을 잡고 있는지 한 번에 설명되지 않았습니다.
- 상태값만으로는 왜 현재 상태가 됐는지 복원하기 어려웠습니다.
- 운영자는 backlog를 읽는 일과 backlog를 청소하는 일을 동시에 떠안아야 했습니다.

즉 moderation은 검수 기능이 있는 서비스였지만, 사람이 처리하는 backlog를 시스템적으로 통제하는 **운영형 queue**는 아니었습니다.

---

## 2. 구조적 판단

이번 고도화의 핵심은 moderation을 더 똑똑하게 만드는 것이 아니라, **backlog를 설명 가능하게 통제할 수 있는 운영형 queue로 바꾸는 것**이었습니다.

그래서 queue의 현재 상태, 점유 회수, 운영 이력, `SLA` 관측을 한 덩어리로 뭉개지 않고 각각의 책임으로 분리해 다시 설계했습니다.

핵심 판단은 아래 다섯 가지였습니다.

### 2-1. queue listing은 읽기 책임만 가져야 합니다

queue 조회가 전역 만료 회수를 겸하면, 운영자는 “지금 backlog를 읽는 중인지”, “읽는 순간 시스템이 상태를 바꾼 것인지”를 분리해서 이해하기 어렵습니다.  
운영형 queue에서는 backlog를 읽는 경로가 숨은 write 경로가 되는 구조를 최대한 줄여야 했습니다.

그래서 queue listing은 **현재 backlog를 보여주는 읽기 책임**만 갖고, 전역 만료 회수는 background 책임으로 분리했습니다.

### 2-2. timeout recovery는 background 책임이어야 합니다

점유 만료 회수가 요청 유입에만 기대면, 요청이 없는 시간대에는 방치된 항목이 그대로 backlog를 막습니다.  
이건 queue가 아니라 “누군가 우연히 다시 볼 때만 정리되는 저장소”에 가깝습니다.

그래서 만료 회수는 **운영자 요청과 무관하게 주기적으로 돌아가는 background recovery**로 뺐습니다.  
다만 개별 액션은 자기 대상 항목의 만료를 마지막으로 한 번 더 보정해, stale lease를 들고 승인·반려하는 일을 막도록 했습니다.

### 2-3. `reassign`는 새 상태가 아니라 운영 전이여야 합니다

재할당은 중요한 운영 절차지만, 새 생명주기를 만들 정도로 독립된 상태는 아니라고 봤습니다.  
이번 범위에서 중요한 건 “검수 중인 항목의 담당자가 바뀌었다”는 사실이지, queue가 새로운 업무 단계로 들어갔다는 뜻은 아니었습니다.

그래서 `reassign`는 아래처럼 정의했습니다.

- 상태는 계속 `UNDER_REVIEW`
- `moderatorId`, `claimedAt`, `claimExpiresAt`만 새 기준으로 갱신
- 운영 의미는 별도 `audit trail`에서 설명

### 2-4. `audit trail`은 본문 상태와 분리돼야 합니다

`moderation_queue`의 최신 row는 “지금 무엇인가”를 답하는 데는 적합하지만, “왜 이렇게 되었는가”를 설명하기에는 부족합니다.  
특히 `LEASE_EXPIRED`, `REASSIGNED`, `UNCLAIMED` 같은 운영 행위는 상태값 하나로는 복원되지 않습니다.

그래서 현재 상태는 `moderation_queue`, 운영 이력은 `moderation_audit_trail`로 분리했습니다.  
이 분리가 있어야 queue의 “현재”와 “왜 그렇게 되었는가”를 서로 다른 질문으로 다룰 수 있습니다.

### 2-5. 1차 범위에서는 고급 정책보다 backlog/SLA/metrics를 먼저 닫아야 했습니다

risk scoring, policy engine, appeal workflow를 먼저 넣으면 겉보기 기능은 많아지지만, backlog가 실제로 어떻게 밀리고 있는지조차 설명하지 못한 채 복잡도만 커집니다.

그래서 이번 단계는 아래를 우선했습니다.

- `24시간 SLA`라는 단일 해석 기준
- 응답에서 바로 보이는 backlog/SLA 상태
- audit event와 맞물리는 메트릭
- 실제 운영 흐름이 닫히는지 보여주는 시나리오 검증

즉, 완성형 moderation platform보다 먼저 **운영 책임을 시스템이 일부 떠안는 최소 기반**을 닫는 데 집중했습니다.

---

## 3. 해결 과정

### 3-1. request-path cleanup을 background recovery로 분리했습니다

기존 구조에서는 만료 회수가 queue 조회나 후속 액션 요청에 기대고 있었습니다.  
이 상태에서는 backlog를 읽는 행위 자체가 cleanup trigger가 되고, 요청 유입이 없으면 방치된 항목이 그대로 남습니다.

그래서 `lease`를 아래처럼 다시 고정했습니다.

- `claim` 시 `status=UNDER_REVIEW`
- 같은 시점에 `moderatorId`, `claimedAt`, `claimExpiresAt`를 세팅
- 기본 `lease` 시간은 `30분`

그 위에 전역 만료 회수 책임을 별도 background 경로로 분리했습니다.

- `ModerationClaimTimeoutRecoveryJob`
- 기본 cron: `hipster.moderation.claim-timeout-recovery-cron=0 * * * * ?`
- 다중 인스턴스 중복 실행 방지를 위한 ShedLock 사용

이제 queue listing은 만료 항목을 전역으로 청소하지 않고, 현재 backlog를 조회만 합니다.  
대신 `approve`, `reject`, `unclaim`, `reassign` 같은 개별 액션은 자기 대상 항목에 대해서만 `persistExpiredClaimReleaseIfNeeded()`를 호출해, stale lease를 들고 잘못 처리하지 않도록 보정합니다.

이 분리는 단순한 코드 정리가 아니라 **읽기 경로와 cleanup 경로를 분리한 운영 책임 분리**였습니다.

또 하나 중요했던 것은 동시성입니다.  
background recovery가 생기면 이제 queue에는 운영자 액션 외에 별도의 백그라운드 write 경로가 하나 더 생깁니다.

- `ModerationQueue`에 `@Version` 추가
- recovery는 항목별 `TransactionTemplate` + `saveAndFlush()`로 처리
- recovery 중 충돌이 나면 해당 항목만 건너뜀
- 요청 경로에서 optimistic locking 충돌이 나면 `GlobalExceptionHandler`를 통해 `409 Conflict`로 노출

즉, 회수 경로와 운영자 액션이 경쟁해도 조용히 덮어쓰지 않고 **충돌을 드러내는 쪽**을 택했습니다.

### 3-2. `reassign`를 공식 경로로 만들되 상태 모델은 불필요하게 늘리지 않았습니다

운영자가 backlog를 다루려면 단순 `claim`과 `unclaim`만으로는 부족합니다.  
근무 종료, 전문성 부족, 급한 backlog 처리 같은 이유로 공식적인 담당 전환이 필요합니다.

그래서 `reassign`를 별도 운영 경로로 추가했습니다.

- 현재 담당자 본인 또는 `ADMIN`만 요청 가능
- 대상은 `MODERATOR` 또는 `ADMIN` 역할 사용자만 가능
- 현재 담당자와 같은 사용자에게는 재할당 불가
- 처리 가능 상태는 `UNDER_REVIEW` + 현재 담당자 존재인 경우만 허용

중요한 점은 여기서도 상태를 새로 만들지 않았다는 것입니다.

- `UNDER_REVIEW`는 유지
- `moderatorId`, `claimedAt`, `claimExpiresAt`만 새 기준으로 갱신
- 인계 의미는 `REASSIGNED` audit event로 남김

이 선택 덕분에 상태 모델은 과도하게 비대해지지 않았고, 운영자는 우회 절차 없이 backlog를 공식 경로로 넘길 수 있게 됐습니다.

다만 이번 1차 범위에서는 재할당 사유 텍스트 입력까지는 넣지 않았습니다.  
현재 구조가 보장하는 것은 “누가 누구에게 넘겼는가”까지이고, 더 자세한 인계 사유는 다음 단계 과제로 남겼습니다.

### 3-3. 상태값만으로 설명 안 되는 운영 절차를 `audit trail`로 분리했습니다

queue item 하나만 보고는 아래를 설명하기 어렵습니다.

- 지금 `PENDING`인 항목이 원래 미처리였는지, lease 만료로 회수된 것인지
- 현재 `UNDER_REVIEW`가 처음 점유인지, 재할당 후 상태인지
- `REJECTED`가 수동 반려인지, 스팸 자동 반려인지

그래서 `moderation_audit_trail`을 별도 엔티티로 두고 아래 정보를 누적 기록하게 만들었습니다.

- `queueItemId`
- `eventType`
- `actorId`
- `previousStatus`, `currentStatus`
- `previousModeratorId`, `currentModeratorId`
- `reason`, `comment`
- `occurredAt`

이벤트 타입도 queue 운영 흐름을 그대로 반영합니다.

- `CLAIMED`
- `UNCLAIMED`
- `LEASE_EXPIRED`
- `REASSIGNED`
- `APPROVED`
- `REJECTED`

이 기록은 단순 로그가 아닙니다.  
최신 row가 “현재 무엇인가”를 답한다면, audit trail은 “누가, 언제, 어떤 운영 절차를 거쳐 이렇게 되었는가”를 복원하는 본 기록입니다.

특히 이번 단계에서는 audit를 비동기 분석 로그로 밀어내지 않았습니다.

- queue 상태 저장과 audit 저장을 같은 트랜잭션 안에 둠
- background recovery도 항목별 트랜잭션 안에서 상태 반납과 audit 저장을 함께 처리

그 이유는 이 단계의 audit가 분석용 부가 로그가 아니라 **설명 가능성을 지탱하는 본 이력**에 더 가까웠기 때문입니다.

### 3-4. 운영자가 backlog와 SLA를 응답에서 바로 읽게 만들었습니다

운영형 queue는 “항목이 있다”보다 “지금 무엇이 늦었는가”를 빠르게 보여줘야 합니다.  
그래서 `SLA`를 숫자 하나로만 두지 않고, backlog를 설명하는 공통 기준으로 재사용했습니다.

현재 기준은 `hipster.moderation.sla-hours=24`입니다.  
이 값은 단순 설정이 아니라, open item backlog를 해석하는 공통 기준입니다.

- item 응답
  - `moderatorId`
  - `claimedAt`
  - `claimExpiresAt`
  - `submittedAgeHours`
  - `slaBreached`
- list 응답
  - `totalPending`
  - `totalUnderReview`
  - `totalSlaBreached`
  - `slaTargetHours`

여기서 중요한 건 `24시간`이라는 값 자체보다, **같은 기준을 API와 metrics가 공유한다는 점**입니다.  
운영자는 개별 항목 응답으로도, backlog 총계 응답으로도 같은 질문에 같은 기준으로 답을 받게 됩니다.

또한 이번 단계의 backlog 가시성은 별도 projection을 만들지 않았습니다.

- 정답 데이터는 여전히 `moderation_queue`
- list 응답은 queue row 조회 + 상태별 count query로 계산
- `SLA` 초과 개수도 open item 기준 DB count로 계산

즉, 1차 범위에서는 새로운 읽기 모델을 더 만드는 대신 **운영 해석 기준을 먼저 고정하는 쪽**을 택했습니다.

### 3-5. 메트릭과 시나리오 검증으로 실제 운영 흐름이 닫히는지 확인했습니다

응답만 잘 보여준다고 운영형 queue가 되는 것은 아닙니다.  
운영자는 API 밖에서도 backlog와 핵심 액션을 읽을 수 있어야 하고, 설계가 실제 흐름 안에서 맞물리는지도 검증돼야 합니다.

그래서 moderation 전용 메트릭을 붙였습니다.

- `moderation.queue.actions{event_type=...}`
- `moderation.queue.backlog{bucket=...}`

이 메트릭도 장식처럼 붙이지 않았습니다.

- action counter는 `ModerationAuditEventType` 의미를 그대로 따름
- `recordAuditTrail()` 공통 경로에서 연결
- 트랜잭션 동기화가 켜져 있으면 `afterCommit` 이후에만 증가
- rollback된 액션이 조용히 집계되지 않도록 방지

backlog gauge는 현재 상태를 별도 projection이 아니라 DB count로 읽습니다.

- `bucket=pending`
- `bucket=under_review`
- `bucket=sla_breached`

이 구조 덕분에 API 응답의 backlog 의미와 Prometheus 메트릭의 backlog 의미가 어긋나지 않게 됐습니다.

검증도 기능별 단위 테스트에서 멈추지 않고 운영 시나리오로 올렸습니다.

- `timeout recovery -> 재점유 -> approve`
- `reassign -> SLA 초과 조회 -> reject`

이 시나리오 테스트는 아래를 함께 확인합니다.

- 방치된 항목이 background recovery 후 다시 backlog로 복귀하는가
- 새 담당자가 다시 점유해 최종 처리할 수 있는가
- 재할당 후에도 `slaBreached`가 응답에서 유지되는가
- audit trail과 metrics가 같은 흐름 안에서 함께 기록되는가

즉, 이번 단계는 기능 목록을 늘린 것이 아니라 **운영 가능한 queue로서의 흐름이 실제로 닫히는지**를 테스트로 증명한 단계였습니다.

---

## 4. 핵심 성과

| 관점 | 기존 | 현재 | 운영 의미 |
| --- | --- | --- | --- |
| 점유 만료 책임 | 요청 유입 의존 cleanup | background recovery + 자기 대상 만료 보정 | 요청 유입이 없어도 방치된 backlog를 회수할 수 있습니다. |
| 담당 전환 | `unclaim -> 재claim` 우회 절차 | 공식 `reassign` 경로 | 운영자가 담당 인계를 시스템 안에서 처리할 수 있습니다. |
| 상태와 이력 | 최신 상태 위주 | `moderation_queue` + `moderation_audit_trail` 분리 | 현재 상태와 그 상태에 이른 경로를 따로 설명할 수 있습니다. |
| `reassign` 의미 | 상태 모델로 설명 어려움 | `UNDER_REVIEW` 유지 + lease 갱신 + audit 기록 | 상태를 늘리지 않고도 운영 전이를 표현할 수 있습니다. |
| backlog / `SLA` 가시성 | 응답만으로 판단 어려움 | item/list 응답에 `submittedAgeHours`, `slaBreached`, backlog 총계 노출 | 운영자가 무엇이 이미 늦었는지 바로 읽을 수 있습니다. |
| 메트릭 의미 | moderation 전용 기준 부재 | audit 의미를 따르는 action counter + DB count 기반 backlog gauge | 응답과 메트릭이 같은 backlog 의미를 공유합니다. |
| 동시성 안전성 | 별도 백그라운드 write 경로 가정 없음 | `@Version`, 항목별 recovery transaction, optimistic-lock 충돌 노출 | 회수와 운영자 액션이 경쟁해도 조용한 덮어쓰기를 줄일 수 있습니다. |
| 검증 수준 | 기능별 단위 확인 중심 | 운영 시나리오 서비스 테스트까지 확장 | queue가 실제 운영 흐름을 감당하는지 증명할 수 있습니다. |

이 문서에서 중요한 성과는 처리량 숫자보다도, **운영 리스크를 사람이 전부 떠안지 않아도 되는 구조로 바꿨다**는 점입니다.

---

## 5. 현재 구조

이 구조의 핵심은 moderation item 하나에 모든 책임을 얹지 않고, **현재 상태, 운영 이력, 회수, `SLA` 집계, 메트릭 노출을 분리해 운영 가능한 queue로 만든 것**입니다.

```text
submission
  -> moderation_queue
     - status
     - moderatorId / claimedAt / claimExpiresAt
     - submittedAt / processedAt
     - @Version
  -> claim / unclaim / reassign / approve / reject
  -> moderation_audit_trail
     - CLAIMED / UNCLAIMED / LEASE_EXPIRED / REASSIGNED / APPROVED / REJECTED
  -> ModerationClaimTimeoutRecoveryJob
     - expired UNDER_REVIEW claims -> PENDING
  -> queue response
     - submittedAgeHours / slaBreached
     - totalPending / totalUnderReview / totalSlaBreached / slaTargetHours
  -> metrics
     - moderation.queue.actions{event_type=...}
     - moderation.queue.backlog{bucket=...}
```

현재 구조를 운영 관점에서 읽으면 아래처럼 정리할 수 있습니다.

- `moderation_queue`는 “지금 backlog가 어떤 상태인가”를 답합니다.
- `moderation_audit_trail`은 “누가 무엇을 해서 이렇게 되었는가”를 답합니다.
- recovery job은 “방치된 점유를 누가 회수하는가”를 답합니다.
- queue 응답은 “지금 SLA를 넘긴 적체가 얼마나 되는가”를 답합니다.
- 메트릭은 “응답을 직접 보지 않아도 backlog와 운영 행동을 어떻게 읽는가”를 답합니다.

즉 moderation은 더 이상 검수 요청을 쌓아두는 저장소가 아니라, 사람이 처리하는 backlog를 **현재 상태와 운영 이력을 분리해 설명 가능한 queue**로 다루게 됐습니다.

---

## 6. 최종적으로 얻은 것

- 방치된 `UNDER_REVIEW` 항목을 요청 유입 없이도 회수할 수 있게 됐습니다.
- backlog를 읽는 경로가 전역 cleanup을 겸하지 않도록 분리해 요청 경로의 숨은 write를 줄였습니다.
- 운영자는 공식 `reassign` 경로로 담당 전환을 처리할 수 있게 됐습니다.
- 특정 항목이 왜 지금 상태가 됐는지 상태 row와 audit trail을 함께 읽어 설명할 수 있게 됐습니다.
- queue 응답과 Prometheus 메트릭에서 같은 `SLA` 기준으로 backlog를 동시에 이해할 수 있게 됐습니다.
- moderation은 단순 기능 묶음이 아니라, backlog를 설명 가능하게 통제하는 **운영형 queue의 최소 기반**을 갖추게 됐습니다.

즉, 이번 고도화는 moderation을 “검수 기능이 붙은 저장소”에서 “시스템과 사람이 함께 backlog를 통제하는 queue”로 끌어올린 작업이었습니다.

---

## 7. 남겨둔 것

이번 단계의 목표는 완성형 운영 플랫폼이 아니라, **사람이 처리하는 backlog를 최소한 설명 가능하게 통제할 수 있는 queue 기반을 코드로 닫는 것**이었습니다.

그래서 아래 항목은 의도적으로 다음 단계로 남겼습니다.

- audit trail 조회 API
  - 이번 1차 범위에서는 “이력이 빠짐없이 써지는가”가 먼저였습니다.
  - 쓰기 경로와 운영 시나리오 검증이 닫히기 전 조회 API를 먼저 열면, 그럴듯한 화면만 있고 정작 기록 신뢰도는 부족한 상태가 될 수 있었습니다.

- 운영자별 처리량, 평균 대기 시간, 평균 점유 지속 시간
  - 이번 단계는 backlog와 `SLA` 초과 상태를 읽는 최소 계측을 먼저 고정하는 범위였습니다.
  - 운영자별 편중 분석이나 파생 평균값은 그 다음 단계의 해석 지표로 두는 편이 더 안전했습니다.

- 더 자세한 재할당 사유와 인계 거버넌스
  - 현재는 누가 누구에게 넘겼는지까지는 남지만, 왜 넘겼는지의 세밀한 운영 문맥 입력은 아직 없습니다.
  - 이건 backlog 통제 기반이 안정화된 뒤 붙이는 것이 더 적절했습니다.

- risk scoring, policy engine, appeal workflow
  - 이런 기능은 backlog control, lease recovery, audit, `SLA` 기준이 먼저 안정화돼야 의미가 있습니다.
  - 기반이 없는 상태에서 정책만 늘리면 운영 복잡도만 커지고 설명 가능성은 더 흐려질 수 있습니다.

- controller/API 통합 검증
  - 이번에는 mock 기반 서비스 테스트와 시나리오 테스트로 핵심 운영 흐름을 빠르게 닫았습니다.
  - security, controller, persistence 경계를 모두 묶는 무거운 통합 검증은 다음 단계로 남겼습니다.

즉, 이번 단계는 “무엇이 가능해졌는가”보다 먼저 “무엇을 시스템이 책임지기 시작했는가”를 닫는 작업이었습니다.

---

## 8. 관련 코드

- `src/main/java/com/hipster/moderation/service/ModerationQueueService.java`
- `src/main/java/com/hipster/moderation/domain/ModerationQueue.java`
- `src/main/java/com/hipster/moderation/domain/ModerationAuditTrail.java`
- `src/main/java/com/hipster/moderation/job/ModerationClaimTimeoutRecoveryJob.java`
- `src/main/java/com/hipster/moderation/metrics/ModerationMetricsRecorder.java`
- `src/main/java/com/hipster/moderation/repository/ModerationQueueRepository.java`
- `src/main/java/com/hipster/moderation/controller/ModerationController.java`
- `src/test/java/com/hipster/moderation/service/ModerationQueueServiceTest.java`
- `src/test/java/com/hipster/moderation/service/ModerationQueueServiceScenarioTest.java`

승인 이후 reward 적립 연계 경계는 별도 주제로 다뤘기 때문에, 그 연결은 `portfolio/reward-ledger.md`를 함께 보면 가장 자연스럽습니다.

---

## 9. 한 줄 요약

moderation을 단순 요청 저장소에서 `lease`, timeout recovery, `reassign`, `audit trail`, `SLA`, 운영 메트릭을 갖춘 운영형 queue로 승격해, backlog의 현재 상태와 운영 이력을 분리하면서 사람이 처리하는 backlog를 설명 가능하게 통제할 수 있는 구조를 만들었습니다.
