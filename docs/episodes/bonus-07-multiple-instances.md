# Bonus Episode 07 — More Than One Instance

Git tag: `bonus-07-multiple-instances` · Builds on: [Bonus Episode 06 — Centralized Logs](bonus-06-centralized-logs.md) · Next: [Bonus Episode 08 — Alerting and SLOs](bonus-08-alerting-slos.md)

## Goal

The service must run as several instances behind a load balancer, for
availability and for load, with the same guarantees as one instance.

Earlier episodes listed the gaps as known shortcuts:

| Listed in | Gap |
|---|---|
| B2 | Two reconciliation schedulers may ask about the same payment. |
| B3 | Two relays may send the same outbox row, and may interleave one payment's events. |
| B3 | The outbox only grows. |
| B4 | No refund reconciliation: a refund whose answer was lost stays `PROCESSING`. |

This episode closes all four. The application layer doesn't learn that there
is more than one instance. Every change is in infrastructure, except refund
reconciliation, which is a use case B4 promised.

## What is already safe

Most of the service needs nothing, because its state lives in PostgreSQL and
Kafka, not in the instance:

| Concern | Why a second instance changes nothing |
|---|---|
| HTTP requests | Stateless. The caller is in the JWT (B1), not in a session, so any instance can answer any request. |
| Idempotency keys | A primary key in PostgreSQL (Episode 10). Two instances inserting the same key at once: one wins, the other sees the conflict. |
| Status changes | Compare-and-set (`... WHERE status = :expected`, Episodes 11 and 13), or `SELECT ... FOR UPDATE` on the payment for refunds (B4). The database decides who wins. |
| `payment-processing` consumer | One consumer group (Episode 17). Kafka gives each partition to exactly one instance's consumer. |

The topics are auto-created with the broker's default of **one partition**.
In a consumer group, a partition is consumed by at most one member. With one
partition, one instance does all of `payment-processing`'s work and the
others' consumers sit idle as standbys. If that instance dies, Kafka hands the
partition to another within the session timeout. More partitions let more
instances work in parallel: the payment-ID key still keeps each payment's
messages on one partition, in order. The partition count caps the number of
consumers that do work, and it's hard to lower later, so it's a capacity
decision made up front. Here it isn't tuned (see Known shortcuts).

What *isn't* safe is everything that runs on a timer. Every instance has the
same `@Scheduled` methods, and nothing coordinates them.

## The relay: claim rows, don't lock the job

Before, the relay read unpublished rows, sent them, and marked them
published. With two instances, both read the same rows and both send them.
Consumers deduplicate by `eventId` (B3), so it was correct, just wasteful.
Ordering was the real problem, as shown further down.

### `FOR UPDATE SKIP LOCKED`

Each relay run is now one transaction that **claims** its batch:

```sql
SELECT * FROM payment_outbox o
WHERE o.published_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM payment_outbox earlier
                  WHERE earlier.payment_id = o.payment_id AND earlier.published_at IS NULL AND earlier.id < o.id)
ORDER BY o.id
LIMIT :max
FOR UPDATE SKIP LOCKED
```

- `FOR UPDATE` locks the returned rows until the transaction ends.
- `SKIP LOCKED` makes another relay's query step over rows that are locked,
  rather than wait for them. It gets the next unclaimed rows instead.

```text
relay A   BEGIN; claim rows 1-5 ── send 1..5 ── mark 1..5 published ── COMMIT
relay B   BEGIN; claim rows 6-10 (1-5 skipped) ── send ── mark ── COMMIT
```

Two relays never send the same row while both are alive. If one dies
mid-batch, its transaction rolls back, the locks go, and the rows are
claimable again on the next run.

The query is native SQL in `OutboxJpaRepository`. JPQL has no `SKIP LOCKED`.
The repository method is `@Transactional(propagation = MANDATORY)`: called
outside a transaction, the locks would be released the moment the query
returned, and the claim would mean nothing. `PaymentEventRelay` opens the
transaction with `TransactionOperations`, as the services do.

### What `SKIP LOCKED` alone gets wrong: ordering

`SKIP LOCKED` stops duplicates. It doesn't keep order. Say payment X has two
unpublished events, `PAYMENT_COMPLETED` (row 1) and `REFUND_COMPLETED` (row 2):

```text
relay A   claims row 1 (X: PAYMENT_COMPLETED)  ── Kafka slow, ack takes 2s ──────── sends
relay B          skips row 1, claims row 2 (X: REFUND_COMPLETED) ── sends at once
                                                                     ^ arrives first
```

A consumer sees the refund before the payment it refunds. Both rows were
claimed correctly. The problem is that they were claimed by different relays.

The fix is the `NOT EXISTS` line. A row is claimable only if no **earlier
unpublished** row of the same payment exists. While row 1 is unpublished,
row 2 isn't a candidate for anyone. Relay B doesn't skip it because it's
locked; the query never returns it. Once A commits row 1 as published, row 2
becomes the oldest and the next run picks it up.

Within one batch the old rule (stop at the first failed send) kept the order.
Across relays only this rule does. `MultipleInstancesTest` shows both halves:
with only `SKIP LOCKED`, its ordering test fails, and with neither, the
exactly-once test fails too.

`DISTINCT ON (payment_id)` would express "oldest per payment" too, but
PostgreSQL doesn't allow `FOR UPDATE` with `DISTINCT`. `NOT EXISTS` works with
it. The subquery looks only at unpublished rows, which are normally a handful.

**The trade.** A payment with several unpublished events publishes one per
run, so its third event waits up to three relay intervals (3 seconds by
default). That's rare (a payment and its refunds, a few seconds apart) and
cheap. The alternative is one relay for the whole fleet, which is a single
bottleneck that also needs a lock.

### The claim is held while sending

The rows stay locked while the batch is sent, so the transaction lasts up to
`relay-batch-size` sends. Each send has the producer's deadlines (Episode
17): `delivery.timeout.ms` is 5s. When a send fails, the relay stops, rather
than wait out a 5s deadline for each of the other rows while holding the
claim. The rows sent so far commit as published. B3's reason for stopping
("nothing may overtake the failed row") is now covered by the `NOT EXISTS`
rule, and stopping only saves time.

### At least once, still

The relay can die after the broker's ack and before the commit that sets
`published_at`. Then the row is unpublished again and the next run sends it
again. `SKIP LOCKED` prevents duplicates between *live* relays, not after a
crash. Consumers still deduplicate by `eventId`.

## Reconciliation: one runner per run

Reconciliation is a different kind of job. One run should look at **every**
stuck payment. Splitting stuck payments across instances would gain nothing,
because there are few of them and each costs one question to the payment
system. What's wanted is: whichever instance's clock fires first does the
run, and the others skip theirs.

That's a lock on the **job**, not on rows. It comes from
[ShedLock](https://github.com/lukas-krecan/ShedLock) (`shedlock-spring` and
`shedlock-provider-jdbc-template`, 7.10.1), and the lock is a row in
PostgreSQL:

```sql
-- V8__create_shedlock.sql
CREATE TABLE shedlock (
    name       VARCHAR(64)  PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
```

Taking the lock is, roughly, `UPDATE shedlock SET lock_until = ... WHERE name = ?
AND lock_until <= now()`. One instance's update matches the row. The others match
nothing, so they skip this run. No new infrastructure is needed: the database
everyone already shares is the coordinator.

```java
// infrastructure/adapter/primary/scheduling/ReconciliationScheduler
@Scheduled(fixedDelayString = "${payment.reconciliation.interval}")
@SchedulerLock(name = "reconciliation", lockAtMostFor = "${payment.reconciliation.lock-at-most-for}",
        lockAtLeastFor = "${payment.reconciliation.lock-at-least-for}")
void run() {
    reconcilePaymentsUseCase.reconcilePayments(threshold);
    reconcileRefundsUseCase.reconcileRefunds(threshold);
}
```

```java
// config/SchedulerLockConfiguration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
...
new JdbcTemplateLockProvider(... .usingDbTime() ...)
```

`usingDbTime()` takes `now()` from PostgreSQL, not from each instance. Two
instances whose clocks disagree by a few seconds still agree on when a lock
expires.

### Row claims vs a job lock

| | Relay | Reconciliation |
|---|---|---|
| Work | Many independent rows | One sweep over everything stuck |
| Splitting it | Natural: each relay takes different rows | Pointless: few rows, one question each |
| Coordination | `SKIP LOCKED` on the rows | ShedLock on the job |
| All instances work? | Yes, in parallel | One per run; the rest skip |

A third option is **leader election**: one instance is elected (a Kubernetes
`Lease`, say) and runs every scheduled job until it dies. That works too, but
it's a bigger mechanism (and B13's subject is Kubernetes). Per-job locks are
simpler here, and they let different jobs run on different instances.

### `lockAtMostFor` and `lockAtLeastFor`

```properties
payment.reconciliation.lock-at-most-for=10m
payment.reconciliation.lock-at-least-for=30s
```

- **`lockAtMostFor` (10m)** is how long the lock lasts if its holder never
  releases it, because the instance was killed or the run hangs. After that,
  another instance takes over. It must be longer than any healthy run.
  A run asks about each stuck payment with a 6s deadline (1s connect + 5s
  read). A few dozen stuck payments are seconds of work, and 10m is far
  above that. If it were too short, a slow run would lose its lock and a
  second run would start alongside it.
- **`lockAtLeastFor` (30s)** keeps the lock for this long even when the run
  finishes in 50ms. Each instance's scheduler fires at its own moment. Without
  this setting, instance A runs and releases, and then B, C and D each find
  the lock free a few hundred milliseconds later and run again. With half the
  1-minute interval, the fleet runs about once per interval, not once per
  instance.

### The compare-and-set stays

B2 made reconciliation correct with several instances: two runs that settle
the same payment race on the compare-and-set, and one wins. ShedLock doesn't
replace that. It saves the duplicate question to the payment system. If a
lock ever expired early (an instance paused longer than `lockAtMostFor`), two
runs would overlap, and the outcome would still be stored once.
Correctness never depended on the lock.

## Refund reconciliation

B4 left refunds whose answer was lost in `PROCESSING`, still counted against
the payment, with a `SHORTCUT:` comment pointing here. They now get the same
treatment as payments.

### A separate use case

```text
application/port/primary/ReconcileRefundsUseCase
application/service/command/ReconcileRefundsService
```

The other choice was a second loop inside `ReconcilePaymentsService`. A refund
is its own aggregate (B4), with its own repository, its own audit table and
its own events. The two reconciliations share nothing except the clock that
triggers them. So they are two use cases, and the scheduler calls both under
one lock. One run settles payments, then refunds.

### Asking about a refund

```java
// PaymentExecutionPort
Outcome findRefundOutcome(Refund refund);
```

```java
// PaymentExecutionClient
@GetMapping("/refund-orders/{idempotencyKey}")
PaymentOrderResponse findRefund(@PathVariable String idempotencyKey);
```

B4 sent each refund with its **refund ID** as the `Idempotency-Key`, so that's
what reconciliation asks by. `PaymentExecutionAdapter` answers it with the
same code as `findOutcome`. The two now share one private `ask(...)`: `404` is
`NOT_RECEIVED`, no answer is `UNKNOWN`, there's no Retry, and a call refused by
the circuit breaker is `UNKNOWN`.

WireMock (`wiremock/mappings/refund-orders.json`) gets the query mapping: any
`GET /refund-orders/{id}` answers `SETTLED`, as the `17.17` refund was after
its answer was lost.

### Same rules as B2

| Answer | Refund | Why |
|---|---|---|
| `SETTLED` | `COMPLETED` | The money went back. |
| `REJECTED` | `FAILED` | Frees the amount for a new refund. |
| never received (404) | `FAILED` | No money moved yet. The refund-ID idempotency key stops a duplicate order, not a delayed first one. See Known shortcuts. |
| no answer | stays `PROCESSING` | The money may have gone back. It still counts against the payment. The next run asks again. |

A settled refund is stored the way `RefundPaymentService.store` stores it: in
one transaction, the compare-and-set from `PROCESSING`, a `refund_audit` row,
and a `REFUND_COMPLETED` (or `REFUND_FAILED`) event in the outbox. The event
needs the payment's accounts, so the service reads the payment first. The
foreign key guarantees it exists.

`RefundRepository.findProcessingSince(before)` finds stuck refunds by
`status_changed_at`, like payments, with the same threshold.

## Outbox cleanup

Published rows used to stay forever. They are now deleted after a retention
period, by a second scheduled method on the relay:

```java
@Scheduled(fixedDelayString = "${payment.events.cleanup-interval}")
@SchedulerLock(name = "outbox-cleanup")
void deletePublished() {
    // DELETE ... WHERE id IN (SELECT id ... WHERE published_at < :before ORDER BY id LIMIT 1000)
    // repeated until a batch deletes fewer than 1000
}
```

```properties
payment.events.retention=7d
payment.events.cleanup-interval=1h
```

**Why not delete right after publishing?** A published row is the evidence of
what was sent and when. If a consumer loses events, say a ledger restored
from yesterday's backup, an operator re-publishes them by setting
`published_at` back to `NULL` for the rows in question, and the relay sends
them again. That only works while the rows exist. Seven days covers a weekend
and a slow incident review. The audit tables are the permanent record; the
outbox is a delivery queue with a short memory.

**Why bounded batches?** A single `DELETE` of a week's rows would run as one
long transaction. It would bloat the WAL and fight the relay for the table.
Batches of 1,000, each its own short transaction, keep every step small.

**Why a lock?** Two instances would both delete the same rows. That would
still be correct, because a delete is idempotent, but it's pointless work and
lock contention. It's the same reasoning as reconciliation, so it uses the
same tool. `lockAtLeastFor` isn't set, because an extra cleanup run costs
nothing.

## Architecture

```text
config/SchedulerLockConfiguration                         @EnableSchedulerLock, the JDBC LockProvider
infrastructure/adapter/primary/scheduling/
   ReconciliationScheduler                                 @SchedulerLock: the job lock lives on the clock
infrastructure/adapter/secondary/messaging/
   PaymentEventRelay                                       the claim transaction; cleanup's @SchedulerLock
infrastructure/adapter/secondary/persistence/
   OutboxJpaRepository                                     FOR UPDATE SKIP LOCKED, the bounded DELETE
application/service/command/
   ReconcilePaymentsService, ReconcileRefundsService       unchanged rules: compare-and-set, one transaction each
```

ShedLock annotations sit on the infrastructure schedulers, never on
application services. A use case is "settle stuck refunds", and *how often*
and *on which instance* are deployment concerns. `SKIP LOCKED` sits in the
persistence adapter. No class under `application/` or `domain/` imports
ShedLock or knows how many instances are running.

## What changed

```text
pom.xml                                                           + shedlock-spring, shedlock-provider-jdbc-template 7.10.1
src/main/resources/db/migration/V8__create_shedlock.sql           new
src/main/resources/application.properties                         + payment.reconciliation.lock-at-most-for, .lock-at-least-for,
                                                                    payment.events.retention, .cleanup-interval
wiremock/mappings/refund-orders.json                              + GET /refund-orders/{id}: SETTLED
src/main/java/com/example/payment/
├── config/SchedulerLockConfiguration.java                        new
├── application/
│   ├── port/primary/ReconcileRefundsUseCase.java                 new
│   ├── port/secondary/PaymentExecutionPort.java                  + findRefundOutcome
│   ├── port/secondary/RefundRepository.java                      + findProcessingSince
│   └── service/command/
│       ├── ReconcileRefundsService.java                          new
│       └── RefundPaymentService.java                             comment: the SHORTCUT points here now
└── infrastructure/adapter/
    ├── primary/scheduling/ReconciliationScheduler.java           + @SchedulerLock, + refunds
    └── secondary/
        ├── feign/PaymentExecutionClient.java                     + findRefund
        ├── feign/PaymentExecutionAdapter.java                    + findRefundOutcome; findOutcome shares ask(...)
        ├── messaging/PaymentEventRelay.java                      claim transaction; + deletePublished
        └── persistence/
            ├── OutboxJpaRepository.java                          claimUnpublished (native), deletePublishedBefore
            ├── OutboxPersistenceAdapter.java                     findUnpublished -> claimUnpublished; + deletePublishedBefore
            ├── RefundJpaRepository.java                          + findByStatusAndStatusChangedAtLessThanEqual
            └── RefundPersistenceAdapter.java                     + findProcessingSince
```

## Known shortcuts

- **No partition tuning.** The topics have one partition each, so one
  instance consumes `payment-processing` and the others stand by. Create the
  topics with more partitions (a `NewTopic` bean, or the platform's topic
  management) when one consumer can't keep up.
- **No CDC.** The relay still polls. Debezium reading the WAL is still B3's
  upgrade path, and it would replace the relay, its claim query and its
  cleanup with a connector.
- **One event per payment per run.** See "The trade" above. A payment with a
  burst of events drains at one per relay interval.
- **No index for stuck refunds.** `findProcessingSince` scans `refund` by
  status. Refunds are few. Add `(status, status_changed_at)`, as `payment`
  has, if the query shows up.
- **A paused instance can outlive its lock.** If an instance pauses (a long
  GC, a frozen VM) for longer than `lockAtMostFor`, another instance starts a
  run alongside it. The compare-and-set keeps the outcome right. Only the
  duplicate questions come back.
- **A very late refund order.** As B2's "very late order", and worse:
  `FAILED` frees the amount, so if the original refund order arrives after
  the `404` and executes, a new refund of the same amount over-refunds the
  payment. The fix is the same: cancel the refund ID with the payment system
  before failing it.

## Try it

```bash
docker compose up -d                       # the infrastructure, as in Episode 19
./mvnw spring-boot:run                                                  # instance 1 on :8080
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081   # instance 2, in a second terminal
```

1. `SELECT * FROM shedlock;` in the `payments` database (`docker compose exec
   postgres psql -U payments payments`). There's a `reconciliation` row whose
   `locked_at` moves on about once a minute: one run per interval for both
   instances together, not one each. (`locked_by` is the hostname, the same
   for both here.)
2. Create and execute payments against both ports ("Episode 09" in
   [http/payments.http](../../http/payments.http), changing the port for half
   of them). Watch `payment-events` as in B3: each event once.
3. Create a refund of `17.17` on a completed payment. It comes back
   `PROCESSING`. Backdate it so you don't wait the 2-minute threshold:
   `UPDATE refund SET status_changed_at = now() - interval '1 hour' WHERE status = 'PROCESSING';`
   Within a minute one instance logs "Refund ... reconciled from PROCESSING
   to COMPLETED", and `REFUND_COMPLETED` arrives on `payment-events`.
4. Stop instance 1 with Ctrl+C mid-interval. Instance 2 takes over
   reconciliation from its next tick. With `kill -9` instead, it waits for
   `lockAtMostFor` if a run was holding the lock.

## Tests

| Test | Change |
|---|---|
| `MultipleInstancesTest` | **new, 3.** Two application contexts (two "instances") against one PostgreSQL, one Kafka and one WireMock. The relays run every 20ms with batches of 5, so they are almost always claiming at the same moment. 40 events recorded: each arrives on `payment-events` exactly once. 30 payments with two events each: each payment's events arrive in the order they were recorded. 10 payments stuck `PROCESSING`: all end `COMPLETED`, and the payment system is asked about each exactly once. Checked by breaking the code: without `SKIP LOCKED` and `NOT EXISTS`, the first two fail; with `SKIP LOCKED` alone, the ordering test fails; without `@SchedulerLock`, payments are asked about twice. |
| `ReconcileRefundsServiceTest` | **new, 4.** Settled → `COMPLETED` with its audit row, a `REFUND_COMPLETED` event naming the refund, and the metric. Never received → `FAILED` with `REFUND_FAILED`. No answer → stays `PROCESSING`, nothing stored. Another run won the compare-and-set → nothing audited or published. |
| `OutboxPersistenceAdapterTest` | **+2.** Real PostgreSQL. Only a payment's oldest unpublished event is claimed, and the second is claimable once the first is published. The cleanup deletes a row published 8 days ago and keeps one published yesterday and one unpublished. |
| `PaymentEventRelayTest` | **+1.** The cleanup deletes batch after batch until one comes back short. The failure test is renamed: the relay stops at the first failed send because the broker is likely down; ordering is now the query's job. |
| `PaymentEndToEndTest` | **+1.** A `17.17` refund comes back `PROCESSING`. Backdated, it is reconciled by a scheduler run to `COMPLETED`, with a `PROCESSING → COMPLETED` audit row, `REFUND_COMPLETED` after `PAYMENT_COMPLETED` on `payment-events`, one `GET /refund-orders/{refundId}`, and still only one `POST`. |

243 tests, passing.
