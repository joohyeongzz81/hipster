# settlement = pay and reconcile
> reward가 `내부 적립 원장을 안전하게 확정하는 문제`였다면, settlement는 `외부 지급 경계까지 포함해 최종 상태를 맞추는 문제`다.  
> 핵심은 자동 정산이 아니라, `정산 요청이 한 번만 잡히고`, `외부 지급 결과가 늦거나 뒤틀려도`, `결국 내부 상태와 외부 상태가 맞아지는 구조`를 만드는 것이다.

---

## 1. 왜 reward 다음에 settlement가 필요한가

reward까지 오면 사용자는 이미 보상을 적립받는다.

- moderation 승인
- reward accrual
- 사용자 적립 잔액 조회

하지만 여기까지는 어디까지나 `내부 원장` 이야기다.

- 이 돈이 지금 정산 가능한가
- 이미 다른 정산 요청이 먼저 잡아간 돈은 아닌가
- 외부 지급 호출이 timeout 났는데 실제 송금은 됐는가
- 성공 웹훅 뒤에 실패 웹훅이 오면 어떻게 해석할 것인가
- 내부 상태와 외부 상태가 어긋나면 무엇을 source of truth로 볼 것인가

이 질문들은 reward의 연장선이지만, reward만으로는 닫히지 않는다.  
그래서 settlement는 `reward 다음의 자연스러운 후속 서사`이되, 책임은 reward와 분리된 별도 경계로 가져가야 한다.

---

## 2. 이 주제의 메인 축은 자동 정산이 아니다

`자동 정산 배치`만으로는 메인 주제가 약하다.

- 조건을 만족하면 배치가 요청을 만든다
- 상태를 바꾼다
- 재시도한다

이 정도면 “정산 시스템”이라기보다 “배치 자동화”에 가깝다.

이 문서에서 다루는 메인 축은 아래다.

- 사용자가 정산 요청을 만든다
- 같은 돈이 두 번 예약되지 않는다
- 외부 지급 호출은 한 번만 의미 있게 실행된다
- 외부 결과가 늦거나 중복돼도 결국 최종 상태가 맞아진다
- 입금 이후 어긋남은 수정이 아니라 adjustment로 남긴다

즉 이 포트폴리오는 `자동 정산`이 아니라 `pay and reconcile`이다.

---

## 3. 도메인 경계

### reward

- 승인 기반 적립 원장
- accrual / reversal
- 총 적립 잔액 조회

reward는 `적립의 진실 공급원`까지 책임진다.  
지급 성공 여부는 reward가 소유하지 않는다.

### settlement core

- available balance
- settlement request
- settlement allocation
- adjustment

settlement core는 `무엇을 왜 지금 지급 가능한가`를 책임진다.

### payout edge

- payout outbox
- external gateway
- webhook inbox
- reconciliation

payout edge는 `외부 지급 결과를 어떻게 관찰하고 다시 맞추는가`를 책임진다.

이 구조를 한 줄로 줄이면 아래다.

`reward -> settlement core -> payout edge`

---

## 4. 핵심 도메인 규칙

### 4-1. 총 적립 잔액과 정산 가능 금액은 다르다

정산 가능 금액은 아래를 뺀 결과다.

- hold 기간 안에 있는 최근 accrual
- 다른 정산 요청이 이미 예약한 금액
- 아직 해소되지 않은 debit adjustment

즉 사용자가 reward에서 보는 총 적립 잔액이 곧바로 payout 대상 금액은 아니다.

### 4-2. 정산 요청은 독립 비즈니스 단위다

정산은 단순 차감이 아니라 `SettlementRequest`라는 단위를 가진다.

- request no
- user id
- requested amount
- reserved amount
- destination snapshot
- provider reference
- status

이 요청이 있어야 외부 지급 결과, 웹훅, 대사, 조정을 한 흐름으로 묶을 수 있다.

### 4-3. 정산 요청 생성 시 금액은 예약된다

정산 가능 금액을 읽고 끝나는 게 아니라, 요청 생성 시점에 `SettlementAllocation`으로 실제 reward entry를 묶어야 한다.

이 예약이 없으면

- 같은 사용자가 중복 요청을 보낼 수 있고
- 같은 reward entry가 두 요청에 동시에 잡힐 수 있고
- 배치나 다중 인스턴스 환경에서 같은 돈이 다시 선택될 수 있다

### 4-4. 내부 성공과 외부 성공은 다르다

`outbox dispatch 성공`은 `송금 성공`이 아니다.

- timeout
- 늦은 성공 웹훅
- 중복 웹훅
- 역순 웹훅
- provider 상태 조회 결과

를 거쳐서야 최종 상태가 정해진다.

그래서 settlement request 상태는 아래처럼 열린다.

- `REQUESTED`
- `RESERVED`
- `SENT`
- `UNKNOWN`
- `SUCCEEDED`
- `FAILED`
- `NEEDS_ADJUSTMENT`

### 4-5. 입금 이후 오차는 adjustment로 남긴다

reward에서는 reversal row가 자연스럽지만, settlement는 실제 지급이 한 번 외부로 나가면 이야기가 달라진다.

- 성공 뒤 실패 웹훅
- 외부 지급 결과 정정
- 사후 차감 필요

이 상황은 기존 row 수정이 아니라 `append-only adjustment`로 남겨야 한다.

---

## 5. 구현한 최소 흐름

현재 구현 범위는 `사용자 요청형 settlement 축소판`이다.

1. `available balance`를 계산한다.
2. 사용자가 정산 요청을 생성한다.
3. 이 시점에 reward entry들이 allocation으로 예약된다.
4. payout outbox가 생성된다.
5. execution worker가 mock payout gateway로 지급을 시도한다.
6. webhook inbox가 중복 웹훅을 한 번만 받아들인다.
7. reconciliation worker가 `SENT / UNKNOWN` 요청을 다시 조회해 최종 상태를 맞춘다.
8. 성공 뒤 실패가 오면 `NEEDS_ADJUSTMENT`와 `OPEN debit adjustment`를 남긴다.
9. 다음 settlement request가 생성되면 열린 debit adjustment를 resolve한다.

핵심은 `실제 은행/PG 없이도 외부 경계가 있는 것처럼 축소판을 재현`했다는 점이다.

---

## 6. 분산 환경을 고려한 하드닝

이번 단계에서 가장 중요했던 것은 “서비스 코드가 조심하는 수준”을 넘는 것이다.

### 6-1. 열린 정산 요청 1건 제약

`SettlementRequest`에는 `open_request_user_id`를 두고 unique constraint를 걸었다.

- open 상태에서는 `userId`를 유지
- finalized 되면 `null`

즉 한 사용자에게 열린 settlement request가 두 건 동시에 생기면 DB가 직접 막는다.

### 6-2. active allocation 중복 금지

`SettlementAllocation`에는 `active_reward_ledger_entry_id`를 두고 unique constraint를 걸었다.

- active reservation이면 reward entry id 유지
- release 되면 `null`

즉 같은 reward entry가 두 열린 정산 요청에 동시에 할당되면 DB가 직접 막는다.

### 6-3. 제약 충돌은 500이 아니라 도메인 conflict다

`SettlementRequestService`는 `DataIntegrityViolationException`을 받아

- 열린 요청 충돌이면 `SETTLEMENT_REQUEST_ALREADY_OPEN`
- 그 외 예약 충돌이면 일반 `CONFLICT`

로 번역한다.

즉 동시 요청 경합이 서버 오류가 아니라 비즈니스 충돌로 해석된다.

---

## 7. 외부 지급 결과를 어떻게 맞추는가

### 7-1. execution

`SettlementExecutionService`는 payout outbox를 dispatch한다.

- success -> `SUCCEEDED`
- timeout -> `UNKNOWN`
- failure -> `FAILED` + allocation release
- gateway exception -> outbox retry

### 7-2. webhook

`SettlementWebhookService`는 먼저 inbox에 저장하고 dedup한다.

- success webhook은 `SENT / UNKNOWN -> SUCCEEDED`
- failure webhook은 `RESERVED / SENT / UNKNOWN -> FAILED`
- success 이후 failure webhook은 `NEEDS_ADJUSTMENT`

즉 webhook은 상태 변경 이전에 `한 번만 소비되는 외부 이벤트 경계`를 가진다.

### 7-3. reconciliation

`SettlementReconciliationService`는 `SENT / UNKNOWN` 요청을 다시 본다.

- provider lookup success -> `SUCCEEDED`
- provider lookup failure -> `FAILED`
- provider lookup timeout -> 그대로 유지

즉 “호출 결과를 모르는 상태”를 장기 방치하지 않고 다시 수렴시킨다.

---

## 8. adjustment는 왜 중요한가

이 포트폴리오에서 adjustment는 부가 기능이 아니라 settlement와 reward를 가르는 기준이다.

reward의 질문:

- 적립이 빠졌는가
- 중복 적립됐는가
- reversal이 필요한가

settlement의 질문:

- 실제 지급이 이미 나갔는데 결과 해석이 뒤집혔는가
- 내부 상태를 고쳐야 하는가
- 다음 정산 흐름에서 무엇을 carry-forward 해야 하는가

그래서 adjustment는 `NEEDS_ADJUSTMENT` 상태와 `SettlementAdjustment` row로 따로 남긴다.

---

## 9. 무엇을 의도적으로 버렸는가

이번 포트폴리오 범위에서 일부러 넣지 않은 것들도 있다.

- 실제 PG / 은행 연동
- 자동 정산 우선 구현
- 복수 통화
- 수수료 / 세금
- 관리자 대시보드
- 계좌 관리 서브도메인

이걸 다 넣으면 settlement가 강해지는 게 아니라, 경계가 흐려지고 구현만 커진다.

이번 포트폴리오의 목표는 `외부 지급 경계가 들어오면 정합성 문제가 어떻게 달라지는가`를 보여주는 것이다.

---

## 10. 코드 증거

핵심 구현 파일은 아래다.

- `src/main/java/com/hipster/settlement/service/SettlementAvailableBalanceService.java`
- `src/main/java/com/hipster/settlement/service/SettlementRequestService.java`
- `src/main/java/com/hipster/settlement/service/SettlementExecutionService.java`
- `src/main/java/com/hipster/settlement/service/SettlementWebhookService.java`
- `src/main/java/com/hipster/settlement/service/SettlementReconciliationService.java`
- `src/main/java/com/hipster/settlement/service/SettlementAdjustmentService.java`
- `src/main/java/com/hipster/settlement/domain/SettlementRequest.java`
- `src/main/java/com/hipster/settlement/domain/SettlementAllocation.java`
- `src/main/java/com/hipster/settlement/domain/SettlementAdjustment.java`
- `src/main/java/com/hipster/settlement/domain/SettlementPayoutOutbox.java`
- `src/main/java/com/hipster/settlement/domain/SettlementWebhookInbox.java`
- `src/main/java/com/hipster/settlement/scheduler/SettlementExecutionJob.java`
- `src/main/java/com/hipster/settlement/scheduler/SettlementReconciliationJob.java`

검증은 아래 테스트로 쌓았다.

- `src/test/java/com/hipster/settlement/domain/SettlementRequestStateMachineTest.java`
- `src/test/java/com/hipster/settlement/domain/SettlementAllocationStateTest.java`
- `src/test/java/com/hipster/settlement/domain/SettlementConstraintJpaTest.java`
- `src/test/java/com/hipster/settlement/service/SettlementRequestServiceTest.java`
- `src/test/java/com/hipster/settlement/service/SettlementExecutionServiceTest.java`
- `src/test/java/com/hipster/settlement/service/SettlementWebhookServiceTest.java`
- `src/test/java/com/hipster/settlement/service/SettlementReconciliationServiceTest.java`
- `src/test/java/com/hipster/settlement/service/SettlementAdjustmentServiceTest.java`

---

## 11. 면접에서 한 줄로 말하면

`reward가 내부 원장 보호 문제였다면, settlement는 외부 지급 경계까지 포함해 같은 돈이 두 번 나가지 않게 예약하고, timeout·웹훅·대사를 거쳐 결국 내부 상태와 외부 지급 결과를 맞추는 문제였습니다.`

---

## 12. 최종 정리

이 settlement 포트폴리오는 `자동 정산`을 과장하지 않는다.  
대신 아래를 분명하게 보여준다.

- 총 적립금과 정산 가능 금액은 다르다
- 정산 요청 생성 시 돈은 예약되어야 한다
- 외부 지급 호출은 성공과 timeout을 구분해야 한다
- 웹훅과 상태 조회를 거쳐 최종 상태가 수렴해야 한다
- 입금 이후 어긋남은 adjustment로 남아야 한다

즉 이 주제의 차별점은 `내부 정합성`이 아니라 `외부 지급까지 포함한 정합성`이다.
