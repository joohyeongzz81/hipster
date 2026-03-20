# 승인과 적립 기록의 경계를 분리하고 Reward Ledger를 유실·중복 없이 관리하기

> moderation `approve`와 reward `accrual`을 한 동기 경계로 묶지 않고, `approve 성공 = 적립 의무(outbox intent) 확정`으로 정합성 대상을 옮긴 뒤, `approvalId` 멱등성과 유니크 제약으로 최종 Reward Ledger를 한 번으로 수렴시키는 구조를 만들었습니다.  
> 핵심은 reward를 느슨한 후처리로 밀어낸 것이 아니라, 적립 의무는 강하게 확정하고 최종 원장 적립은 멱등적으로 수렴시키는 경계를 다시 설계한 것입니다.

이 문서에서 보여드리고 싶은 핵심은 “리워드를 비동기로 바꿨다”가 아닙니다.  
문제의 본질은 reward 경합과 실패가 moderation 승인 경계 전체로 전파되던 구조였고, 이를 `approve`와 `accrual` 사이의 **정합성 경계를 다시 설계한 Reward Ledger**로 바꾼 과정입니다.

Reward는 이 문맥에서 `rating summary` 같은 느슨한 파생 집계가 아닙니다.  
approval과 연결된 사용자 보상 상태이기 때문에, 유실·중복·cap·reversal·자기 조회를 더 강하게 다뤄야 했습니다.

---

## 1. 문제 상황

처음에는 moderation 승인 결과를 reward 적립으로 바꾸는 온라인 경계 자체가 없었습니다.  
그래서 1차 단계인 X001에서는 먼저 `승인 -> Reward Ledger` 본체를 닫았습니다.

이 단계에서 실제 코드로 고정한 기본 원칙은 아래와 같았습니다.

- 적립 입력 식별자는 `ModerationQueue.id`, 즉 `approvalId`로 고정한다.
- 적립 입력은 `APPROVED` 상태만 받는다.
- 같은 승인 입력은 같은 캠페인에서 한 번만 적립된다.
- cap 초과는 조용한 누락이 아니라 0포인트 원장 항목으로 남긴다.
- 잘못된 적립은 기존 row 수정이 아니라 reversal row로 취소한다.
- 사용자는 `/api/v1/reward-ledger/me/balance`, `/api/v1/reward-ledger/me/approvals`로 자기 적립 결과를 직접 볼 수 있어야 한다.

특히 cap 차단과 reversal은 실제 코드에서도 “실패 처리”가 아니라 원장 의미로 남습니다.

- cap 차단 row는 별도 `BLOCKED` 타입이 아니라 `entryType=ACCRUAL`, `entryStatus=CAP_EXCEEDED`, `pointsDelta=0`, `reason=CAMPAIGN_POINT_CAP_EXCEEDED`로 기록됩니다.
- reversal row는 `entryType=REVERSAL`, 음수 `pointsDelta`, `referenceEntryId`로 원래 accrual row를 가리키며, 별도 `reason`을 남깁니다.

즉 X001의 핵심은 “포인트가 들어갔는가”보다 “왜 적립됐고, 왜 차단됐고, 왜 취소됐는가”를 Reward Ledger 기준으로 설명 가능하게 만드는 것이었습니다.

하지만 이 Reward Ledger 경계를 moderation `approve()` 안에 강하게 묶어 두자 다른 문제가 드러났습니다.

- cap 판단과 `grantedPoints` 갱신은 `reward_campaigns`의 단일 기본 캠페인 row를 `pessimistic write`로 잠근 뒤 처리됩니다.
- 그 결과 reward campaign row lock이 생기면 lock wait가 `RewardLedgerService`를 넘어서 `ModerationQueueService.approve()` 완료 시간까지 전파됩니다.
- X001 sync baseline 5회 측정에서는 no-lock 평균 `61ms`, reward campaign lock 존재 시 평균 `447ms`가 나왔습니다.

즉 문제는 단순 latency가 아니라, **별도 원장 경계의 경합과 실패가 moderation 승인 SLA 전체를 오염시키는 blast radius**였습니다.

---

## 2. 구조적 판단

여기서 중요한 질문은 두 가지였습니다.

1. moderation `approve`가 reward 최종 적립 결과까지 같은 동기 경계에서 책임져야 하는가
2. 그렇다고 reward를 `rating summary`처럼 eventual consistency만 보면 되는 파생 집계로 내려도 되는가

제 답은 둘 다 아니었습니다.

reward는 단순 파생 집계가 아닙니다.  
approval과 연결된 사용자 보상 상태이기 때문에, 적립 의무 유실, 중복 적립, cap 초과, reversal, 사용자 설명 가능성을 더 강하게 다뤄야 합니다.

하지만 그렇다고 reward의 최종 row 생성까지 `approve()`의 동기 성공 의미에 묶어 두면, reward 경합과 실패가 moderation 승인 경계를 함께 흔들게 됩니다.  
실제로 X001 baseline은 그 결합 비용이 lock wait로 곧바로 전파된다는 점을 보여줬습니다.

그래서 X002에서는 정합성을 버린 것이 아니라, **강하게 확정할 대상을 최종 적립 결과에서 `적립 의무(outbox intent)`로 옮겼습니다.**

- X001
  - `approve 성공 = ledger 적립까지 동기 완료`
- X002
  - `approve 성공 = reward accrual obligation이 같은 DB 트랜잭션 안에서 안전하게 기록됨`
  - 최종 ledger 적립은 후행 처리
  - 하지만 `approvalId` 멱등성과 유니크 제약으로 한 번으로 수렴

즉 이 단계의 핵심은 “비동기화”가 아니라, **무엇을 같은 트랜잭션 안에서 강하게 확정할지 다시 정의한 것**이었습니다.

---

## 3. 해결 과정

### 3-1. X001에서 먼저 온라인 Reward Ledger 본체를 닫았습니다

먼저 approval을 reward 원장으로 바꾸는 본체부터 만들었습니다.

- `approvalId = ModerationQueue.id`를 적립 입력 기준으로 고정
- 서비스 수준 멱등성으로 같은 `approvalId`의 기존 결과가 있으면 새 row를 만들지 않음
- 저장소 수준 유니크 제약 `(approvalId, campaignCode, entryType)`으로 최종 중복 insert를 차단
- cap 초과는 0포인트 accrual row로 기록
- reversal은 기존 row 수정이 아니라 별도 reversal row로 기록
- `/me/balance`, `/me/approvals`로 사용자 자기 조회 제공

이 단계 덕분에 reward는 “포인트 후처리”가 아니라, approval 단위의 적립·차단·취소를 설명하는 온라인 원장 경계가 됐습니다.

### 3-2. sync baseline으로 coupling cost를 수치로 확인했습니다

그 다음에는 구조적 감이 아니라 실제 병목 전파를 측정했습니다.

- no-lock `approve()` 평균: `61ms`
- reward campaign lock 존재 시 `approve()` 평균: `447ms`
- with-lock 분포는 `445ms ~ 452ms`로 거의 lock wait를 그대로 반영했습니다.

이 수치는 “reward가 좀 느리다”는 뜻이 아니었습니다.  
reward 경합이 moderation 승인 경계의 성공 의미와 응답 시간까지 함께 끌고 들어온다는 뜻이었습니다.

즉 X002는 성능 최적화 문서가 아니라, **왜 `approve-accrual` 경계를 분리해야 하는지에 대한 근거 문서**가 됐습니다.

### 3-3. X002에서 `approve`와 `accrual`을 DB-backed outbox handoff로 분리했습니다

이후 `ModerationQueueService.approve()`는 더 이상 `RewardLedgerService`를 직접 호출하지 않도록 바꿨습니다.

현재 `approve()`가 같은 트랜잭션 안에서 함께 커밋하는 것은 여기까지입니다.

- moderation 상태 변경
- 승인 대상 엔티티 반영
- audit trail 기록
- `reward_accrual_outbox`에 `PENDING` intent 생성

이 outbox row는 실제로 `approval_id + campaign_code` 유니크 제약을 갖고, `PENDING / DISPATCHED / PROCESSED / FAILED` 상태를 가집니다.  
즉 “적립이 언젠가 필요하다”는 추상 메모가 아니라, approval 단위 적립 의무를 DB에 남기는 계약 기록입니다.

그 뒤 실제 적립은 후행 경로로 넘어갑니다.

- `RewardAccrualOutboxPublisher`
  - ready outbox polling
  - dispatch claim
  - RabbitMQ 발행
  - `waitForConfirmsOrDie(...)`로 broker confirm 확인
- `RewardAccrualRabbitConsumer`
  - `RewardLedgerService.accrueApprovedContribution(approvalId)` 수행
  - 성공 시 outbox `PROCESSED`
  - 실패 시 outbox `FAILED`

이 구조 덕분에 reward campaign row lock이 있어도 moderation `approve()`는 outbox intent 확정까지는 완료할 수 있게 됐습니다.

### 3-4. 최종 적립은 `approvalId` 멱등성과 유니크 제약으로 한 번으로 수렴시켰습니다

비동기 구조로 바꾸면 duplicate publish, duplicate consume, consumer downtime은 예외가 아니라 일상 시나리오입니다.  
그래서 중복 방어선도 한 문장으로 뭉개지 않고 두 겹으로 뒀습니다.

- 서비스 수준 멱등성
  - `RewardLedgerService.accrueApprovedContribution(approvalId)`는 먼저 기존 accrual row를 조회합니다.
  - 없으면 기본 캠페인 row lock을 잡은 뒤 다시 한 번 조회합니다.
  - 이미 같은 `approvalId` 결과가 있으면 새로 만들지 않고 기존 row를 반환합니다.
- 저장소 수준 멱등성
  - `reward_ledger_entries`는 `(approvalId, campaignCode, entryType)` 유니크 제약으로 최종 중복 insert를 막습니다.

이 조합 덕분에 duplicate message가 들어와도 최종 `ACCRUAL` row는 한 번으로 수렴합니다.

### 3-5. cap, reversal, 자기 조회 의미도 후행 구조로 약하게 만들지 않았습니다

X002에서 분리한 것은 `approve`와 `accrual`의 동기 결합이지, reward 원장 규칙 자체가 아닙니다.  
후행 구조로 옮긴 뒤에도 아래 의미는 그대로 유지됩니다.

- cap 초과는 0포인트 ledger row로 남는다.
- reversal은 기존 row 수정이 아니라 별도 reversal row로 남는다.
- `/me/approvals`는 approval별 `accrualState`, `netPoints`, `entries`를 내려준다.
- `/me/balance`는 사용자 총 적립 포인트와 캠페인 기준 active participation을 보여준다.

특히 `/me/approvals`는 비동기 gap까지 설명합니다.  
현재 구현은 `ACCRUED`, `CAP_EXCEEDED`, `REVERSED`뿐 아니라, 승인됐지만 아직 원장 항목이 없으면 `MISSING`으로도 보여줄 수 있습니다.

즉 reward는 여전히 “사용자가 자기 승인 기여와 적립 결과를 연결해 볼 수 있는 원장”으로 남아 있습니다.

### 3-6. self-healing은 메인 보장이 아니라 safety net으로 제한했습니다

X002의 메인 보장은 아래 두 가지입니다.

- `approve + outbox intent`를 같은 트랜잭션에서 강하게 확정
- 최종 ledger 적립은 `approvalId` 멱등성으로 한 번으로 수렴

그 위에 최소 복구 장치를 safety net으로만 올렸습니다.

- stale `DISPATCHED` outbox는 다시 `FAILED` retry 후보로 되돌립니다.
- `FAILED` outbox는 `nextAttemptAt`이 되면 publisher가 다시 dispatch합니다.

여기서 중요한 점은, 현재 복구의 중심이 broker redelivery가 아니라 **DB outbox retry**라는 것입니다.

- consumer는 처리 실패 시 outbox를 `FAILED`로 남기고 message는 ack합니다.
- 즉 주 복구 경로는 “브로커가 무한 재전달한다”가 아니라 “outbox poller가 다시 publish한다”입니다.
- outbox 실패 상태 업데이트조차 못 한 경우에만 consumer가 nack/requeue로 fallback합니다.

즉 self-healing은 메인 보장이 무너졌을 때만 기대는 보조 장치이고, 전면 reconciliation은 아직 아닙니다.

---

## 4. 핵심 성과

| 관점 | X001 / 기존 | X002 / 현재 | 의미 |
| --- | --- | --- | --- |
| 적립 입력 기준 | 불명확하거나 동기 처리에 흡수 | `approvalId = ModerationQueue.id` | 승인 1건을 적립 의무 1건으로 고정했습니다. |
| `approve 성공`의 의미 | 최종 ledger 적립까지 동기 완료 | outbox intent 강한 확정 | 정합성을 버리지 않고 확정 대상을 옮겼습니다. |
| 최종 적립 보장 | 동기 트랜잭션에 강결합 | `approvalId` 멱등성 + 유니크 제약 수렴 | duplicate publish/consume에도 최종 row는 한 번으로 모입니다. |
| cap 초과 처리 | 실패처럼 흐르기 쉬움 | 0포인트 accrual row + `CAP_EXCEEDED` | “왜 안 들어갔는가”를 원장으로 설명할 수 있습니다. |
| 취소 처리 | 기존 row 수정 위험 | `referenceEntryId`를 가진 reversal row | “무엇을 왜 되돌렸는가”를 시간순으로 설명할 수 있습니다. |
| 사용자 조회 | 관리자 내부 중심 | `/me/balance`, `/me/approvals` | reward를 제품 의미가 있는 bounded context로 올렸습니다. |
| 승인-적립 blast radius | reward lock과 실패가 `approve()`에 전파 | outbox handoff 이후 reward가 후행 처리 | moderation 경계를 reward 경합에서 분리했습니다. |
| sync baseline 수치 | no-lock `61ms` / with-lock `447ms` | 경계 재설계 근거 확보 | 이 수치는 성능 자랑이 아니라 결합 비용의 증거였습니다. |

여기서 중요한 성과는 latency 절대값이 아니라, **적립 의무는 잃지 않으면서 approval 경계가 reward 경합까지 떠안지 않게 만든 것**입니다.

---

## 5. 현재 구조

이 구조의 핵심은 reward를 “비동기화했다”가 아니라, `approve`는 적립 의무 확정까지만 책임지고 최종 ledger 반영은 멱등 소비로 수렴시키도록 경계를 재설계한 것입니다.

```text
Moderation approve
  -> reward_accrual_outbox(PENDING)
  -> publisher
  -> RabbitMQ
  -> consumer
  -> Reward Ledger
     - ACCRUAL(ACCRUED or CAP_EXCEEDED, 0pt)
     - REVERSAL(referenceEntryId)
  -> /me/balance
  -> /me/approvals
```

이 구조를 운영 관점에서 읽으면 아래처럼 정리할 수 있습니다.

- `approve` 성공은 reward 완료가 아니라 outbox intent가 커밋됐다는 뜻입니다.
- outbox는 `PENDING -> DISPATCHED -> PROCESSED / FAILED` 상태로 handoff와 복구 대상을 드러냅니다.
- 최종 ledger 적립은 `approvalId` 기준으로 한 번만 남습니다.
- cap 차단과 reversal도 모두 ledger row로 남아 설명 가능성을 유지합니다.
- `/me/approvals`는 approval별 상태와 row 목록을 함께 내려, 비동기 gap까지 사용자 기준으로 해석 가능하게 만듭니다.

즉 이 다이어그램은 단순 메시징 흐름이 아니라, **정합성 경계와 설명 책임의 그림**입니다.

---

## 6. 최종적으로 얻은 것

- moderation 승인 결과를 실제 Reward Ledger로 연결하는 온라인 경계를 만들었습니다.
- 적립, 차단, 취소를 모두 원장 항목으로 남겨 “왜 이런 상태인가”를 설명할 수 있게 됐습니다.
- `approve 성공`의 의미를 “최종 적립 완료”에서 “적립 의무 확정”으로 바꿨습니다.
- duplicate publish/consume와 consumer downtime 가능성을 전제로 두고도 최종 ledger를 한 번으로 수렴시켰습니다.
- 사용자도 자기 승인 기여별 적립 결과를 직접 볼 수 있게 됐습니다.
- reward를 moderation에 종속된 후처리가 아니라, approval과 연결된 별도 bounded context로 설명할 수 있게 됐습니다.

즉 reward는 더 이상 “승인 뒤에 붙는 포인트 처리”가 아니라, **approval 단위 적립 의무와 최종 원장 수렴을 함께 책임지는 별도 경계**가 됐습니다.

---

## 7. 남겨둔 것

이번 단계의 목표는 완전한 reward platform이 아니라, **approve와 accrual의 경계를 분리하면서도 적립 의무 유실 없이 최종 ledger를 한 번으로 수렴시키는 최소 원장 구조를 코드로 닫는 것**이었습니다.

그래서 아래 주제는 의도적으로 다음 단계로 남겼습니다.

- broker unavailable 이후 recover 시나리오 심화
  - 현재 코드는 publish/confirm 실패를 `FAILED`로 남기고 retry할 수 있습니다.
  - 다만 “실제 broker 장애 이후 전체 경로가 어떤 운영 절차로 회복되는가”까지는 더 깊은 검증이 남아 있습니다.

- multi-instance dispatch 경쟁 검증 강화
  - 현재는 dispatch claim update로 중복 발행을 줄이지만, 다중 인스턴스에서의 운영 검증은 더 필요합니다.

- 전면 reconciliation
  - 지금 있는 것은 stale `DISPATCHED` 복구와 `FAILED` retry 수준의 safety net입니다.
  - 전체 approval 집합을 다시 대조하는 전면 reconciliation은 아직 메인 보장에 포함하지 않았습니다.

- `defaultCampaignCode` hot row 완화
  - 현재 consumer 경로의 `RewardLedgerService`는 여전히 단일 기본 캠페인 row를 잠그고 cap을 판단합니다.
  - X002는 이 hot row를 없앤 것이 아니라, 그 경합이 `approve()` SLA를 오염시키지 않게 만든 단계입니다.
  - hot row 자체를 줄이는 것은 quota 재설계 주제에 가깝습니다.

- `AUTO_APPROVED` 입력 편입과 추가 캠페인 정책 확장
  - 현재 reward 입력은 `APPROVED`만 받고, `AUTO_APPROVED`는 아직 체계에 넣지 않았습니다.
  - 여러 캠페인 정책으로 확장하는 문제도 기반 안정화 이후 주제로 남았습니다.

즉 이번 단계는 “모든 reward 문제를 끝냈다”가 아니라, **가장 중요한 경계 문제를 먼저 닫았다**고 보는 것이 정확합니다.

---

## 8. 관련 코드

- `src/main/java/com/hipster/reward/service/RewardLedgerService.java`
- `src/main/java/com/hipster/reward/service/RewardAccrualOutboxService.java`
- `src/main/java/com/hipster/reward/domain/RewardLedgerEntry.java`
- `src/main/java/com/hipster/reward/domain/RewardAccrualOutbox.java`
- `src/main/java/com/hipster/reward/event/RewardAccrualOutboxPublisher.java`
- `src/main/java/com/hipster/reward/event/RewardAccrualRabbitConsumer.java`
- `src/main/java/com/hipster/reward/controller/RewardLedgerController.java`
- `src/main/java/com/hipster/moderation/service/ModerationQueueService.java`
- `src/test/java/com/hipster/moderation/service/ModerationApproveRewardOutboxIsolationIntegrationTest.java`
- `src/test/java/com/hipster/reward/event/RewardAccrualOutboxEndToEndIntegrationTest.java`

---

## 9. 한 줄 요약

Reward Ledger를 단순 후처리 이벤트가 아니라 approval과 연결된 별도 bounded context로 설계해, `approve 성공 = 적립 의무(outbox intent) 확정`으로 정합성 경계를 옮기고 `approvalId` 멱등성과 유니크 제약으로 최종 ledger를 유실·중복 없이 한 번으로 수렴시키는 구조를 만들었습니다.
