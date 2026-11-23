# Episode 13 — Transactions and Audit

Git tag: `episode-13-transactions-audit` · Previous: [Episode 12 — Authorization and Limits](12-authorization-limits.md) · Next: [Episode 14 — Architecture Tests](14-architecture-tests.md)

## Goal

Every status change now leaves a permanent record:

```text
payment_audit
payment_id  from_status  to_status   occurred_at
7f3c…       CREATED      AUTHORIZED  2026-10-08 10:15:02.114
7f3c…       AUTHORIZED   PROCESSING  2026-10-08 10:15:02.208
7f3c…       PROCESSING   COMPLETED   2026-10-08 10:15:02.251
```

An audit record that can disagree with the payment is worse than none. So
this episode is really about **transactions**: which writes must succeed or
fail together, and where a transaction must *not* reach.

## The rule: one transaction per status change, no external calls inside

```text
ExecutePaymentService.executePayment          no transaction around the method
    |
    |  PaymentAuthorizationPort.isAuthorized  external call
    |  ┌ tx ─────────────────────────────────┐
    |  │ status CREATED -> AUTHORIZED         │
    |  │ audit  CREATED -> AUTHORIZED         │
    |  └──────────────────────────── commit ──┘
    |  PaymentLimitPort.isWithinLimit         external call
    |  ┌ tx ─────────────────────────────────┐
    |  │ status AUTHORIZED -> PROCESSING      │
    |  │ audit  AUTHORIZED -> PROCESSING      │
    |  └──────────────────────────── commit ──┘
    |  PaymentExecutionPort.execute           external call: money moves
    |  ┌ tx ─────────────────────────────────┐
    |  │ status PROCESSING -> COMPLETED       │
    |  │ audit  PROCESSING -> COMPLETED       │
    |  └──────────────────────────── commit ──┘
```

Why not one `@Transactional` around the whole method?

- **A rollback can't un-send money.** The payment system isn't part of our
  database transaction. If saving `COMPLETED` failed and rolled back
  *everything*, the payment would read `CREATED` again, ready to be executed
  a second time, while the money has already moved.
- **`PROCESSING` must be committed before calling out.** It's what says
  "this may have been sent". Inside one big transaction nobody else could
  see it until the end, and a crash would lose it.
- **A transaction holds a database connection.** Holding one open while
  waiting on three HTTP calls exhausts the pool under load.

So each status change is its own short transaction, wrapped around its audit
record, and nothing else. The services use Spring's `TransactionOperations`
for this, rather than `@Transactional`, because the boundary is a few lines
*inside* a method, not the method itself.

`CreatePaymentService` does the same: the account check runs first, outside
any transaction; then the idempotency claim and the new payment commit
together.

## Rollback scenarios

`PaymentTransactionsTest` makes one write fail on purpose against real
PostgreSQL and checks what is left:

| What fails | Rolled back | Left in the database | What it means |
|---|---|---|---|
| audit of `CREATED -> AUTHORIZED` | the `AUTHORIZED` status | `CREATED`, no audit rows | as if execute never started; retry is safe |
| audit of `PROCESSING -> COMPLETED` | the `COMPLETED` status | `PROCESSING`, two audit rows | money **moved**; we just don't know the outcome. Not rolled back, and can't be executed again |
| saving a new payment | the idempotency claim | nothing | a retry with the same key creates the payment, instead of `409` forever |

The second row is the point of the episode: the database is consistent with
itself, but not with the outside world, and no transaction can fix that.
`PROCESSING` is the honest state. Finding such payments and asking the
payment system what happened is reconciliation, which this tutorial doesn't
build (see Known shortcuts).

## Closing the races

Episodes 09, 11 and 12 left the same race open: two requests both read the
payment, both decide it may move, and the second save overwrites the first.
Two executes could both send the money; a cancel could be overwritten by an
execute.

Each status change is now a compare-and-set:

```sql
UPDATE payment SET status = 'PROCESSING' WHERE id = ? AND status = 'AUTHORIZED'
```

PostgreSQL locks the row for the first update. The second waits, re-checks
the `WHERE` once the first commits, and matches nothing. The service sees
`false`, rolls back its transaction and answers `409`:

```text
Payment 7f3c… was changed by another request and cannot become PROCESSING
```

The domain still decides which moves are allowed; the database only makes
sure a decision taken on stale data can't be stored. `PaymentRepository`
gets one method for this, `updateStatus(payment, expected)`, and `save` is now
only used for new payments.

`PaymentTransactionsTest` runs eight executes of the same payment at once:
one succeeds, seven get `409`, and the audit trail has each step exactly once.

## What changed

```text
src/main/java/com/example/payment/
├── application/
│   ├── port/secondary/
│   │   ├── AuditPort.java                         new
│   │   └── PaymentRepository.java                 + updateStatus(payment, expected)
│   ├── exception/InvalidPaymentStateException.java  + constructor without a cause
│   └── service/command/
│       ├── CreatePaymentService.java              claim + save in one transaction
│       ├── ExecutePaymentService.java             one transaction per status change, audited
│       └── CancelPaymentService.java              compare-and-set + audit in one transaction
└── infrastructure/adapter/secondary/persistence/
    ├── AuditEntity.java                           new
    ├── AuditJpaRepository.java                    new
    ├── AuditPersistenceAdapter.java               new
    ├── PaymentJpaRepository.java                  + updateStatus query
    └── PaymentPersistenceAdapter.java             + updateStatus

src/main/resources/db/migration/
└── V3__create_payment_audit.sql                   new
```

No HTTP changes. The race now answers `409`, the same status a second
execute or cancel already got.

## Known shortcuts

- **Creation isn't audited.** The audit starts at the first transition, as
  the episode's examples do. A `null -> CREATED` row is one line in
  `CreatePaymentService` if it's wanted.
- **Nothing reads the audit yet.** No endpoint, no query port. Add one when
  something needs it; until then, `psql` (below). Added later: see
  "Later addition: reading the trail".
- **Payments stuck in `PROCESSING` are not reconciled.** That needs the
  payment system to answer "what happened to this one?". Episode 16
  (resilience) deals with failing external calls; Episode 17 (asynchronous
  processing) with work that continues after the request.
- **Losing a race can cost an external call.** Two executes may both ask the
  authorization system before one loses the compare-and-set. Asking twice is
  harmless; sending twice isn't, and can't happen.
- **`IdempotencyKeyInProgressException` is now unreachable** in practice: a
  claim is never visible without its payment. It stays as a `409` guard.

## Later addition: reading the trail

After bonus 13, the trail can be read without SQL: `GET /payments/{id}/history`
lists the payment's status changes, `from`, `to` and `at`, oldest first. The
same rule as `GET /payments/{id}` applies: anyone but the source account's
holder gets the same `404`.

It reads through a port of its own, `AuditQueryPort`, so `AuditPort` stays
write-only: the services that change a status can't read the trail back.
Rows come back in insert order (`id`), not by `occurred_at`, which is each
instance's clock. A new index on `payment_id` keeps the read off a full scan.
The history starts at the first change: a payment still `CREATED` has none.
Refunds have their own trail (`refund_audit`) and aren't in it.

## Try it

```bash
./mvnw spring-boot:run
```

In [`http/payments.http`](../../http/payments.http), run the Episode 13
requests to create and execute a payment, then read its audit trail:

```bash
docker compose exec postgres psql -U payments -c \
  "SELECT from_status, to_status, occurred_at FROM payment_audit ORDER BY id DESC LIMIT 3"
```

## Tests

| Test | What it proves |
|---|---|
| `application/service/command/PaymentTransactionsTest` | **New.** Against real PostgreSQL and WireMock: every transition is audited; a failed audit write rolls back its status change; a failure after the money moved leaves the payment `PROCESSING`; a failed save releases the idempotency key; eight concurrent executes send the payment once. |
| `application/service/command/ExecutePaymentServiceTest` | **+1 case.** Losing the compare-and-set is `409` and nothing is sent. Existing cases also check the audit trail. |
| `application/service/command/CancelPaymentServiceTest` | **+1 case.** Losing to an execute is `409` and nothing is audited. The happy path is audited. |
| `infrastructure/adapter/secondary/persistence/PaymentPersistenceAdapterTest` | **+1 case.** `updateStatus` succeeds from the expected status and fails from a stale one. |
| `application/service/command/CreatePaymentServiceTest`, `application/service/query/GetPaymentServiceTest` | Unchanged, except their fakes implement `updateStatus` and run without a transaction. |
