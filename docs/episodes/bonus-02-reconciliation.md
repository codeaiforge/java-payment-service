# Bonus Episode 02 — Reconciliation

Git tag: `bonus-02-reconciliation` · Builds on: [Bonus Episode 01 — Security](bonus-01-security.md) · Next: [Bonus Episode 03 — Payment Events](bonus-03-payment-events.md)

## Goal

Episode 16 made one rule: a timeout is not a "no". When the payment system's
answer is lost, the payment stays `PROCESSING`, because that is the truth:
the money may have moved. Episodes 16, 17 and 20 all listed the same
shortcut. Nothing ever settles such a payment. Someone has to ask the
payment system by hand.

The business rule: a payment left `PROCESSING` after an unknown outcome is
resolved without a person asking the payment system.

## The flow

```text
ReconciliationScheduler (fixed delay)
   |
   v
ReconcilePaymentsService: payments PROCESSING for longer than the threshold
   |
   v
PaymentExecutionPort.findOutcome: GET /payment-orders/{paymentId}
   |
   +-- SETTLED         -> complete()   PROCESSING -> COMPLETED, audit row
   +-- REJECTED        -> fail()       PROCESSING -> FAILED, audit row
   +-- 404             -> fail()       PROCESSING -> FAILED, audit row
   +-- no answer       -> nothing; the next run asks again
```

Each payment gets its own transaction with its audit row (Episode 13), after
the answer is in. No transaction is held open across the HTTP call, the same
rule `ExecutePaymentService` follows. The loop catches each payment's
exception, logs it and goes on: a payment that fails to reconcile doesn't
stop the others.

## The scheduler is a primary adapter

```java
@Scheduled(fixedDelayString = "${payment.reconciliation.interval}")
void run() {
    reconcilePaymentsUseCase.reconcilePayments(threshold);
}
```

[`ReconciliationScheduler`](../../src/main/java/com/example/payment/infrastructure/adapter/primary/scheduling/ReconciliationScheduler.java)
is the whole adapter. It is a primary adapter for the same reason the
controller and the Kafka consumer are: it is a way *in*. Something outside
the application (an HTTP client, a message, here the clock) asks it to do
something. It calls a primary port and decides nothing.

| Primary adapter | Triggered by | Use case |
|---|---|---|
| `PaymentController` | an HTTP request | create, get, execute, cancel |
| `PaymentProcessingConsumer` | a Kafka message | `ExecutePaymentUseCase` |
| `ReconciliationScheduler` | the clock | `ReconcilePaymentsUseCase` |

Put the same logic in the scheduler and it couldn't be tested without
Spring and a clock, and an operator endpoint that reconciles on demand
would have to copy it. Behind the port, either is one more adapter.

`@EnableScheduling` sits on `PaymentApplication`.

## Asking the payment system

[`PaymentExecutionPort`](../../src/main/java/com/example/payment/application/port/secondary/PaymentExecutionPort.java)
gets a second method:

```java
Outcome execute(Payment payment);      // an instruction: moves money
Outcome findOutcome(Payment payment);  // a question: moves nothing
```

and `Outcome` a fourth answer, `NOT_RECEIVED`, which only `findOutcome`
gives. The adapter asks `GET /payment-orders/{paymentId}`. The path is the
same payment ID that `execute` sent as `Idempotency-Key` (Episode 16), so the
payment system knows which order we mean.

| Payment system answers | `Outcome` | Payment |
|---|---|---|
| `200 {"status":"SETTLED"}` | `EXECUTED` | `COMPLETED` |
| `200 {"status":"REJECTED"}` | `REJECTED` | `FAILED` |
| `404` | `NOT_RECEIVED` | `FAILED` |
| timeout, 5xx, open breaker, anything else | `UNKNOWN` | stays `PROCESSING` |

The question goes through the same `payment-system` circuit breaker as
execution. It has no Retry. Asking twice is safe, but the next run asks
again anyway. A `404` is an answer, not a failure, so it doesn't count
against the breaker.

The new [`wiremock/mappings/payment-orders.json`](../../wiremock/mappings/payment-orders.json)
mappings answer `SETTLED` for any order by default, which is what the
`TIMEOUT` stub really did after the answer was lost. Two fixed IDs answer
the other cases, for trying it by hand:

```text
GET /payment-orders/00000000-0000-4000-8000-000000000422  -> REJECTED
GET /payment-orders/00000000-0000-4000-8000-000000000404  -> 404
```

## What failing "never received" guarantees

A `404` means the order hasn't arrived. No money has moved, so `FAILED` is
true when we write it.

The order carries the payment ID as `Idempotency-Key`. The payment system
keeps one outcome per key, so this payment can be paid at most once: a
duplicate order is refused. And this service never sends it again:
`fail()` makes it final, and the domain refuses `PROCESSING` from `FAILED`.
A customer who still wants to pay creates a new payment with a new ID.

What the key does not stop is a *delayed first* order. If the original is
still in flight (a proxy, a queue, a retrying load balancer) when we ask, it
can arrive after the `404` and pay. The threshold gives a slow order time to
arrive before we ask, which makes this unlikely, not impossible. See Known
shortcuts.

## Finding stuck payments

```text
application/port/secondary/PaymentRepository   + findProcessingSince(Instant)
db/migration/V4__add_status_changed_at.sql     + payment.status_changed_at, index (status, status_changed_at)
```

"Stuck" means `PROCESSING` and last changed before *now − threshold*. The
persistence adapter stamps `status_changed_at` on `save` and in the
compare-and-set `updateStatus`. The domain doesn't hold this timestamp:
no rule in `Payment` depends on it. It is a fact about the row, used to find
rows, so it stays in the adapter. Payments that exist before the migration
get the migration's time.

## Configuration

```properties
payment.reconciliation.interval=1m
payment.reconciliation.threshold=2m
```

The threshold is longer than the payment system's deadline (1s to connect +
5s to read). A run never asks about a payment whose `execute` is still
waiting for an answer, so it never races one. If the deadline grows, the
threshold has to grow with it.

`fixedDelay`, not `fixedRate`: the next run starts an interval after the last
one *finished*, so a slow run can't overlap the next on the same instance.

## Several instances

Two instances run two schedulers, and both may pick the same payment. The
outcome is still correct:

1. Both ask the payment system. It's a question, so asking twice moves no
   money.
2. Both call `complete()` on their own copy. The domain allows it from
   `PROCESSING`.
3. Both store it with the compare-and-set status update in `PaymentJpaRepository` (Episode 13):
   `UPDATE ... SET status = 'COMPLETED' WHERE id = ? AND status = 'PROCESSING'`.
   PostgreSQL lets one through. The other matches no row, writes no audit
   row and logs "already reconciled by another run".

A lock such as ShedLock would only save the duplicate question. It isn't
needed for correctness, so it isn't added. See Known shortcuts.

## Why the Kafka consumer still doesn't retry `PROCESSING`

Retrying is for an instruction that wasn't carried out. A `PROCESSING`
payment's instruction *was* sent, and we don't know what happened. Sending
it again is a guess with money. Asking is the right move, and that is a
different use case on a different schedule: after the threshold, not
straight after a failure. So `ExecutePaymentService` still refuses
`PROCESSING` (the domain allows `PROCESSING` only from `AUTHORIZED`), and a
duplicate or redelivered message still ends there.

## Observability

`PaymentMetricsPort.reconciled(Outcome)` counts each payment a run settles,
by what the payment system said:

```text
payments_reconciled_total{result="executed"}
payments_reconciled_total{result="rejected"}
payments_reconciled_total{result="not_received"}
payments_reconciled_total{result="unknown"}
```

`unknown` is counted on every run that still gets no answer, so a payment
stuck for an hour adds about 58. A steady `unknown` rate means the payment
system is down or doesn't know the endpoint. A `not_received` means an
order was lost on the way, and is worth a look. A settled payment also
counts in `payments_total{status="completed"}` or `"failed"`, as before.
Like every other metric, no tag carries a payment, account or customer.

## What changed

```text
src/main/resources/db/migration/V4__add_status_changed_at.sql   new
src/main/resources/application.properties                        + payment.reconciliation.interval, .threshold
wiremock/mappings/payment-orders.json                            + GET /payment-orders/{id}: SETTLED, REJECTED, 404
src/main/java/com/example/payment/
├── PaymentApplication.java                                      + @EnableScheduling
├── application/
│   ├── port/primary/ReconcilePaymentsUseCase.java               new
│   ├── port/secondary/PaymentExecutionPort.java                 + findOutcome, Outcome.NOT_RECEIVED
│   ├── port/secondary/PaymentRepository.java                    + findProcessingSince
│   ├── port/secondary/PaymentMetricsPort.java                   + reconciled
│   └── service/command/ReconcilePaymentsService.java            new
└── infrastructure/
    ├── adapter/primary/scheduling/ReconciliationScheduler.java  new
    ├── adapter/secondary/feign/PaymentExecutionClient.java      + GET /payment-orders/{key}
    ├── adapter/secondary/feign/PaymentExecutionAdapter.java     + findOutcome
    ├── adapter/secondary/persistence/PaymentEntity.java         + statusChangedAt
    ├── adapter/secondary/persistence/PaymentEntityMapper.java   toEntity takes the time
    ├── adapter/secondary/persistence/PaymentJpaRepository.java  updateStatus stamps it, + findBy...LessThanEqual
    ├── adapter/secondary/persistence/PaymentPersistenceAdapter.java  + findProcessingSince
    └── observability/PaymentMetrics.java                        + payments.reconciled{result}
```

The domain is unchanged: `complete()` and `fail()` from `PROCESSING` have
existed since Episode 09.

## Known shortcuts

- **No lock between instances.** Two schedulers may ask about the same
  payment. The compare-and-set keeps the result right; ShedLock (or
  `SELECT ... FOR UPDATE SKIP LOCKED`) would only save the duplicate call.
  Add it when the payment system charges per query or the count of stuck
  payments gets large.
- **One query reads every stuck payment.** No paging. There should be a
  handful. Page through them if a run ever finds thousands.
- **A very late order.** "Never received" is final, but the idempotency key
  only stops a duplicate, not a delayed first order. If the payment system
  receives the original more than the threshold after we sent it and executes
  it, the payment reads `FAILED` while the money moved. The fix: before
  failing a `NOT_RECEIVED` payment, send the payment system a cancel for that
  payment ID, so a late order with that key is refused, and only then mark it
  `FAILED`. The alternatives are a payment system that tombstones keys it
  reported as unknown, or one that refuses orders older than a deadline. All
  three are a contract with the payment system.
- **An open breaker on execution is still `UNKNOWN`** (Episode 16). Now it
  resolves itself: nothing was sent, so reconciliation finds `404` and the
  payment ends `FAILED` one threshold later. It does not go back to
  `AUTHORIZED` to be tried again.
- **The threshold check is by convention.** Nothing stops someone setting
  the threshold below the read timeout. Then a run could fail a payment
  whose order is still on its way. Change both together.
- **No operator endpoint** to reconcile one payment now. It would be one
  more primary adapter on the same use case.

## Try it

```bash
./mvnw spring-boot:run
```

Then, with the "Episode 16" requests in [http/payments.http](../../http/payments.http):

1. Create a payment with a reference starting `TIMEOUT` and execute it. GET
   it: `PROCESSING`. The payment system took 7 seconds; we stopped listening
   after 5.
2. Wait about three minutes (2m threshold + up to 1m interval). GET it again:
   `COMPLETED`.
3. The log line `Payment ... reconciled from PROCESSING to COMPLETED`, an
   audit row `PROCESSING -> COMPLETED`, and
   `curl -s localhost:8080/actuator/prometheus | grep reconciled` shows
   `payments_reconciled_total{result="executed"} 1.0`.

To wait seconds instead of minutes, start it with
`--payment.reconciliation.interval=5s --payment.reconciliation.threshold=10s`.

## Tests

| Test | Change |
|---|---|
| `ReconcilePaymentsServiceTest` | **new, 7.** Asks for payments older than the threshold; `SETTLED` completes, `REJECTED` and never received fail, each with its audit row and metric; no answer leaves it `PROCESSING`; one payment's exception doesn't stop the run; a payment another run reconciled first gets no audit row. Fakes, no Spring. |
| `PaymentExecutionAdapterTest` | **+2.** `GET /payment-orders/{paymentId}` against WireMock: `SETTLED`, `REJECTED`, `404`; `503` is `UNKNOWN`. |
| `PaymentPersistenceAdapterTest` | **+1.** A status change stamps `status_changed_at`; `findProcessingSince` finds the payment only once it is old enough. Real PostgreSQL, so `V4` is checked too. |
| `PaymentEndToEndTest` | **+1.** A payment left `PROCESSING` by the `TIMEOUT` stub, backdated past the threshold, ends `COMPLETED` after a real scheduler run, with a `PROCESSING -> COMPLETED` audit row, and was sent only once. The scheduler runs every 200ms here. |
| `ExecutePaymentServiceTest`, other service tests | The fakes implement the new port methods; execute never asks, the others never look for stuck payments. |

176 tests, passing.
