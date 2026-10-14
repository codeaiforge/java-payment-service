# Bonus Episode 04 — Refunds

Git tag: `bonus-04-refunds` · Builds on: [Bonus Episode 03 — Payment Events](bonus-03-payment-events.md) · Next: [Bonus Episode 05 — Distributed Tracing](bonus-05-distributed-tracing.md)

## Goal

A customer can refund a completed payment, all at once or in parts:

- only a `COMPLETED` payment can be refunded
- only by the customer who holds its source account (B1). Anyone else gets `404`
- the refund is in the payment's currency
- a payment's refunds never add up to more than its amount, counting
  refunds that are still `PROCESSING`

```http
POST /payments/{paymentId}/refunds        Idempotency-Key, { "amount": 50.00, "currency": "EUR" }   -> 201
GET  /payments/{paymentId}/refunds                                                                 -> 200
```

## Cancelling vs refunding

Episode 11's cancel happens *before* any money moves. It's a status of the
payment (`CANCELLED`), and the domain allows it only from `CREATED` or
`AUTHORIZED`.

A refund comes *after* the money moved. Nothing is undone: it's a new money
movement in the other direction, with its own instruction to the payment
system and its own unknown outcome. The payment stays `COMPLETED`, because
that is still true. It was paid. Some of it was later sent back.

## Domain

```text
domain/entity/Refund
domain/valueobject/RefundId
domain/valueobject/RefundStatus      PROCESSING -> COMPLETED | FAILED
domain/service/RefundPolicy
```

### `Refund` is its own aggregate

The obvious design is a `List<Refund>` inside `Payment`. It's the wrong one:

- **Loading.** Changing one refund's status would load the payment and every
  refund it ever had, just to update one row.
- **Lifecycle.** A refund has its own ID, its own status and its own unknown
  outcome, just as a payment does. It is created, sent and settled on its
  own schedule.
- **Payment doesn't change.** Refunding moves no payment status. If refunds
  lived inside `Payment`, every refund would be a write to the payment
  aggregate, competing with nothing that actually changes it.

So `Refund` holds the ID of its payment, not the other way round. `Payment`
is unchanged in this episode.

`Refund.create` starts at `PROCESSING`, not at some `CREATED`. The refund
is stored just before it is sent, and from then on it *may* have gone
through. There is no moment when a stored refund is certainly unsent. The
same reasoning made Episode 09 store `PROCESSING` before calling out.

### `RefundPolicy`: the first `domain/service`

§3 of the plan: introduce domain services "only when the behavior genuinely
requires them". This is the first time it does.

"The refunds never add up to more than the payment" is a rule about one
`Payment` *and* many `Refund`s. It can't live in `Payment` (which doesn't
know its refunds), or in `Refund` (which doesn't know the others). It still
belongs in the domain, because it's a business rule, not orchestration. So
it lives in a domain service:

```java
public static void check(Payment payment, List<Refund> existing, Money requested)
```

| Situation | Policy says | Application | HTTP |
|---|---|---|---|
| payment not `COMPLETED` | `IllegalStateException` | `InvalidPaymentStateException` | 409 |
| another currency | `IllegalArgumentException` | `PaymentValidationException` | 400 |
| more than is left | `RefundPolicy.AmountExceeded` | `RefundExceedsPaymentException` | 422 |

`PROCESSING` refunds count, because they may still go through. `FAILED` ones
don't, because they moved nothing. `AmountExceeded` extends
`IllegalArgumentException`, so the domain still speaks only in standard
exceptions. The subclass just lets the application tell "too much" (422)
apart from "wrong currency" (400).

The policy is only as good as the list it's given. If another refund is
being added while it decides, the list is already out of date. That's the
application's problem, and the next section is about it.

## Application

```text
application/port/primary/RefundPaymentUseCase, GetRefundsUseCase
application/usecase/command/RefundPaymentCommand     paymentId, amount, currency, customerId, idempotencyKey
application/usecase/query/GetRefundsQuery            paymentId, customerId
application/service/command/RefundPaymentService
application/service/query/GetRefundsService
application/port/secondary/RefundRepository          save, updateStatus, findByPaymentId, findByIdempotencyKey
application/port/secondary/PaymentRepository         + findByIdForUpdate
application/port/secondary/PaymentExecutionPort      + refund(Refund)
application/port/secondary/AuditPort                 + recordRefundTransition(RefundId, from, to)
application/port/secondary/PaymentMetricsPort        + refundFinished(RefundStatus)
application/exception/RefundExceedsPaymentException  -> 422
```

### The flow is synchronous

```text
RefundPaymentService.refundPayment
   ├── findById + isHeldBy                           no transaction: the account system is an external call
   ├── 1. transaction
   │      ├── SELECT ... FOR UPDATE payment          findByIdForUpdate
   │      ├── a refund with this key already?        -> return it (replay)
   │      ├── RefundPolicy.check(payment, refunds, amount)
   │      ├── INSERT refund PROCESSING               the key's unique constraint claims it
   │      └── INSERT refund_audit (null -> PROCESSING)
   ├── 2. PaymentExecutionPort.refund(refund)        no transaction, no lock
   └── 3. transaction
          ├── UPDATE refund SET status = COMPLETED | FAILED WHERE status = PROCESSING
          ├── INSERT refund_audit
          └── INSERT payment_outbox (REFUND_COMPLETED | REFUND_FAILED)
```

Episode 17 made execution asynchronous because it's a chain: authorization,
then limits, then the payment system, each of which can be slow or down,
and nobody needs the answer before the response. A refund is one call, with
no authorization or limit step, and the customer is waiting to see whether
the money went back. So the request does the work and answers `201` with
the refund as it ended: `COMPLETED`, `FAILED`, or `PROCESSING` when the
answer was lost.

No transaction is held across step 2, for the same reason as in Episode 13:
a rollback can't take back money that moved, and a transaction waiting 5
seconds for the payment system would hold a database connection, and here a
lock, for all that time.

### Why a lock, when everything so far used compare-and-set

Every status change so far is a compare-and-set:
`UPDATE ... WHERE status = :expected`. It works because the invariant is
about **one row**. Of two requests, the second finds the status already
changed and updates nothing.

The refund invariant is about rows that **don't exist yet**:

```text
request A (150)                          request B (150)
SELECT refunds -> none, 250 left         SELECT refunds -> none, 250 left
policy: 150 <= 250, ok                   policy: 150 <= 250, ok
INSERT refund 150                        INSERT refund 150
commit                                   commit                -> 300 refunded on a 250 payment
```

There's no existing row whose status could be compared. Both inserts
succeed. So step 1 starts with `SELECT ... FOR UPDATE` on the **payment**
row (`PaymentRepository.findByIdForUpdate`). It's the one row every refund
of that payment has in common. B waits at that line until A commits, then
reads A's `PROCESSING` refund, and the policy refuses. Refunds of *other*
payments don't wait at all.

The same two requests with the lock:

```mermaid
%%{init: {"sequence": {"mirrorActors": false}}}%%
sequenceDiagram
    participant A as Refund request A (150)
    participant DB as PostgreSQL
    participant B as Refund request B (150)

    Note over A,B: payment of 250, COMPLETED, nothing refunded yet
    A->>DB: SELECT payment … FOR UPDATE
    DB-->>A: locked
    B->>DB: SELECT payment … FOR UPDATE
    rect rgba(100, 116, 139, 0.15)
    Note over B: waits: A holds the row lock
    A->>DB: SELECT refunds → none, 250 left
    Note over A: RefundPolicy: 150 ≤ 250, ok
    A->>DB: INSERT refund 150 PROCESSING
    A->>DB: commit: lock released
    end
    Note over A: then calls the payment system, without the lock
    DB-->>B: locked
    B->>DB: SELECT refunds → A's 150, 100 left
    Note over B: RefundPolicy: 150 > 100, refused
    B->>DB: rollback: nothing stored
    Note over B: 422: more than is left to refund
```

The lock is held only for step 1: a few reads and two inserts. It's never
held across the payment system call. `PaymentTransactionsTest` shows the race
on real PostgreSQL: two concurrent refunds of 150 on a 250 payment, and
exactly one succeeds. Remove the lock and both do.

The lock doesn't slow execute or cancel: they use compare-and-set, not
`FOR UPDATE`. They can't conflict anyway, since only a `COMPLETED` payment is
refunded, and a `COMPLETED` payment never changes again.

### Idempotency

`Idempotency-Key` is required, as for creating a payment (Episode 10), and
scoped to the customer and hashed the same way (B1, `CreatePaymentService.sha256`).

The `idempotency_key` table isn't reused. Its `payment_id` column names the
payment a key *created*. A refund key creates a refund, so the column would
hold the wrong kind of ID. Instead the `refund` row carries its own
`idempotency_key` with a `UNIQUE` constraint, so inserting the refund *is*
claiming the key. There's no separate claim to keep in step with it.

- **Replay.** The same key, payment and amount return the existing refund,
  `201`, whatever its status. Nothing is sent again.
- **Same key, different refund.** A different payment or amount is a `422`,
  as for payments.
- **Two at once.** The same key on the same payment is serialized by the
  lock, and the second request finds the first's row. The same key on two
  different payments takes two different locks. The insert is
  `ON CONFLICT (idempotency_key) DO NOTHING`, so the loser stores nothing
  and gets a `422`, instead of a constraint error that would abort its
  transaction.

## Payment system

```http
POST /refund-orders
Idempotency-Key: {refundId}
{ "originalPaymentId": "...", "amount": 50.00, "currency": "EUR" }
```

Its answer is `SETTLED` or `REJECTED`. `PaymentExecutionAdapter.refund` treats
it exactly like `execute`: no Retry, and a timeout, a 5xx or an answer it
doesn't understand is `UNKNOWN`. The key is the **refund** ID. The payment
ID is already the key of the original order.

One difference: an open circuit breaker. For execute, that's reported
`UNKNOWN` (a known shortcut since Episode 16). For a refund, the customer is
waiting and the refund was certainly not sent, so the adapter throws
`ExternalSystemUnavailableException`. The service then stores the refund as
`FAILED`, which frees its amount, and the customer gets `503`. A retry needs
a new key, because the old one now names a failed refund.

WireMock (`wiremock/mappings/refund-orders.json`): an amount of `13.13` is
`REJECTED`, `17.17` answers after 7s (unknown), and anything else is
`SETTLED`.

## API errors

| Case | Status |
|---|---|
| unknown payment, or another customer's | 404 |
| payment not `COMPLETED` | 409 |
| other currency, amount ≤ 0 or more than 2 decimals, missing key | 400 |
| more than is left to refund; same key, different refund | 422 |
| payment system known to be down, refund not sent | 503 |

## Persistence

```text
db/migration/V6__create_refund.sql
   refund        (id, payment_id FK, amount, currency, status, idempotency_key UNIQUE, created_at, status_changed_at)
   refund_audit  (id, refund_id FK, from_status, to_status, occurred_at)
   payment_outbox + refund_id
```

`refund_audit.from_status` is null on the row that records the refund being
created. A payment's creation was never audited (Episode 13 audits
transitions), but a refund is created already `PROCESSING`, already
possibly sent, so its first row matters.

`RefundPersistenceAdapter` implements `RefundRepository`. The refund audit
rows are written by `AuditPersistenceAdapter`, through a native `INSERT` on
`AuditJpaRepository`. The table is insert-only, so it gets no entity.

## Events (B3)

`PaymentEventType` gains `REFUND_COMPLETED` and `REFUND_FAILED`.
`PaymentEvent` gains `refundId`, which is null for a payment's own events,
and a refund event carries the **refund's** amount. The factory is
`PaymentEvent.refundFinished(payment, refund, at)`. Like `finalStatusReached`,
it refuses a refund that isn't final.

The event is recorded in step 3's transaction, so the outbox guarantee
from B3 holds as before. It's keyed by the payment ID, so a consumer sees
`PAYMENT_COMPLETED` before any of that payment's refund events.

`PaymentEventMessage` gains `refundId`. A consumer that ignores unknown
fields doesn't notice.

## Observability

`PaymentMetricsPort.refundFinished(status)`: the counter
`refunds_total{status="completed"|"failed"}`. It counts refunds, never
amounts. A refund failing to reach the payment system also counts in
`external_payment_errors_total{system="refund"}`. Logs carry refund and
payment IDs only.

## What changed

```text
src/main/resources/db/migration/V6__create_refund.sql             new
wiremock/mappings/refund-orders.json                              new
src/main/java/com/example/payment/
├── domain/
│   ├── entity/Refund.java                                        new
│   ├── valueobject/RefundId.java, RefundStatus.java              new
│   ├── service/RefundPolicy.java                                 new, the first domain service
│   └── event/PaymentEvent.java, PaymentEventType.java            + refundId, refundFinished, REFUND_*
├── application/
│   ├── port/primary/RefundPaymentUseCase, GetRefundsUseCase      new
│   ├── port/secondary/RefundRepository.java                      new
│   ├── port/secondary/PaymentRepository.java                     + findByIdForUpdate
│   ├── port/secondary/PaymentExecutionPort.java                  + refund
│   ├── port/secondary/AuditPort.java                             + recordRefundTransition
│   ├── port/secondary/PaymentMetricsPort.java                    + refundFinished
│   ├── usecase/command/RefundPaymentCommand.java                 new
│   ├── usecase/query/GetRefundsQuery.java                        new
│   ├── service/command/RefundPaymentService.java                 new
│   ├── service/query/GetRefundsService.java                      new
│   └── exception/RefundExceedsPaymentException.java              new
└── infrastructure/
    ├── adapter/primary/web/PaymentController.java                + POST, GET /payments/{id}/refunds
    ├── adapter/primary/web/RefundRequest, RefundResponse.java    new
    ├── adapter/secondary/feign/PaymentExecution{Client,Adapter}  + /refund-orders
    ├── adapter/secondary/persistence/Refund{Entity,JpaRepository,
    │   PersistenceAdapter}.java                                  new
    ├── adapter/secondary/persistence/PaymentJpaRepository        + findByIdForUpdate (PESSIMISTIC_WRITE)
    ├── adapter/secondary/persistence/Audit*, Outbox*             + refund audit, + refund_id
    ├── adapter/secondary/messaging/PaymentEventMessage.java      + refundId
    └── observability/PaymentMetrics.java                         + refunds counter
```

`AuditPort` has two methods now, so it's no longer a functional interface.
The service tests that used a lambda for it share a small fake,
`AuditTrail`.

## Known shortcuts

- **No refund reconciliation.** A refund whose answer was lost stays
  `PROCESSING`, and its amount stays reserved. B2's reconciliation applies
  unchanged in shape: find refunds `PROCESSING` past a threshold, ask
  `GET /refund-orders/{refundId}`, settle them. It isn't built here.
- **One lock per payment.** Concurrent refunds of the *same* payment run one
  at a time through step 1. That's a few milliseconds each, and a payment
  isn't refunded in parallel bursts. Refunds of different payments don't
  wait for each other.
- **A replay of a `FAILED` refund is still `201 FAILED`.** The key names that
  refund. A new attempt needs a new key.
- **No total on the payment.** `GET /payments/{id}` doesn't show how much has
  been refunded. `GET /payments/{id}/refunds` lists the refunds, and the
  client adds them up.

## Try it

```bash
docker compose up -d && ./mvnw spring-boot:run
```

1. Create and execute a payment ("Episode 09" in
   [http/payments.http](../../http/payments.http)), and paste its ID into
   `@refundPaymentId`.
2. Run the "Bonus 04" requests: refund 100.00 (`201 COMPLETED`), then 150.00,
   then 0.01 (`422`). List them (`200`, two refunds). Refund 13.13 on a
   fresh payment to see `FAILED`.
3. On the `payment-events` consumer from B3, a `REFUND_COMPLETED` line
   follows the payment's `PAYMENT_COMPLETED`, with the same key.

## Tests

| Test | Change |
|---|---|
| `RefundTest` | **new, 3.** `PROCESSING` to `COMPLETED` or `FAILED`; a finished refund never changes again; amount must be positive. |
| `RefundPolicyTest` | **new, 11.** Full; partials up to the amount; one cent over; `PROCESSING` counts, `FAILED` doesn't; wrong currency; every status other than `COMPLETED` is refused. |
| `PaymentEventTest` | **+2.** Refund events carry the refund's ID and amount; none while `PROCESSING`. |
| `RefundPaymentServiceTest` | **new, 10.** Fakes, no Spring. Settled, rejected, unknown (stays `PROCESSING` and still counts); payment system down (`FAILED`, 503); over the amount; replay; same key, different refund; another customer's payment; not `COMPLETED`; 400s. |
| `PaymentTransactionsTest` | **+1.** Real PostgreSQL: two concurrent refunds that together exceed the payment, exactly one succeeds. |
| `PaymentExecutionAdapterTest` | **+3.** `/refund-orders` with the refund ID as key; a timeout is `UNKNOWN` and sent once; an open circuit sends nothing and throws. |
| `PaymentControllerPortTest` | **+2.** Request to command, refund to `201`; over the amount is `422`. |
| `PaymentEndToEndTest` | **+4.** Full refund in two parts, then `422`; `409` for a payment not `COMPLETED`; `404` for another customer; `REFUND_COMPLETED` on `payment-events` after `PAYMENT_COMPLETED`. |

223 tests, passing.
