# Bonus Episode 09 — Payment Statement (Read Model)

Git tag: `bonus-09-payment-statement` · Builds on: [Bonus Episode 08 — Alerting and SLOs](bonus-08-alerting-slos.md)

## Goal

A customer can list the settled payments of an account they hold: newest
first, filtered by status and dates, a page at a time. Each line shows the
payment, its final status, and how much of it has been refunded.

```http
GET /accounts/{accountId}/statement?status=&from=&to=&page=&size=     -> 200, 400, 403
```

B4 listed this as a shortcut: "No total on the payment. `GET
/payments/{id}` doesn't show how much has been refunded." The statement shows
it.

The write side's business rules don't change.

## Why not query the write model

The `payment` table is shaped for what the write side does: find one payment
by its ID, lock it, move it through its state machine. A statement asks a
different question: every payment of one account, by date, each with the sum
of its refunds. Answering that from the write tables means a join to
`refund`, a `GROUP BY`, and an index on the payment table for a query the
write side never makes.

So the statement gets its own model, shaped for its one query, and kept up to
date from the events B3 already publishes. That is CQRS at the scale of one
service: the **command** side (payments, refunds, their rules) and a **query**
side (the statement) with separate models. It isn't a separate service or a
separate database, just separate tables that only the projection writes.

It has two costs:

- **Eventual consistency.** The statement learns about a payment from
  `payment-events`, after the outbox relay has sent it (up to a second) and
  the consumer has applied it. A payment completed a moment ago may not be on
  it yet. `GET /payments/{id}` reads the write model and is always current.
- **A second copy of the data.** It takes up space, and it can be wrong if
  the projection has a bug. Because the copy is built only from events, it
  can be thrown away and rebuilt (Rebuild, below).

## Read model

`V9__create_payment_statement.sql`:

```text
statement_line              one row per payment, from its source account's side
  payment_id                primary key
  account_id                whose statement it's on
  counterparty_account_id
  amount, currency          the payment's; amount null until its own event arrives
  status                    COMPLETED | FAILED | CANCELLED; null until then
  refunded_amount           completed refunds, summed
  settled_at                when it reached that status
  last_event_id             the last event applied to it
statement_processed_event   every event applied, by event ID
```

No foreign key points at `payment`. The statement can be emptied and rebuilt
without the write side noticing.

### The projection is application logic

```text
StatementProjectionConsumer (primary adapter, Kafka, group payment-statement)
  ReceivedPaymentEvent -> PaymentEvent
  -> ProjectPaymentEventUseCase.project(event)
     ProjectPaymentEventService: what the event does to the line
     -> StatementRepository (secondary port) -> StatementPersistenceAdapter (JPA)
```

The consumer is a primary adapter like `PaymentProcessingConsumer`. It reads
the message and calls a use case. The rules are in
`ProjectPaymentEventService`:

| Event | The line |
|---|---|
| `PAYMENT_COMPLETED`, `PAYMENT_FAILED`, `PAYMENT_CANCELLED` | status, amount and `settled_at` from the event |
| `REFUND_COMPLETED` | refunded amount + the refund's amount |
| `REFUND_FAILED` | nothing changes; the event is still recorded as applied |
| any event for a payment with no line | creates the line first |
| an event already applied | nothing |

These rules have unit tests with an in-memory statement, and no Kafka or
database.

The consumer reads `payment-events` through its own record,
`ReceivedPaymentEvent`, and doesn't reuse the publisher's
`PaymentEventMessage`. This service publishes the topic and reads it, but it
reads it the way any other consumer would: through the JSON contract, not the
publisher's class.

### Idempotent by event ID

Kafka delivers at least once. The event's ID goes into
`statement_processed_event` (`INSERT ... ON CONFLICT DO NOTHING`) **in the
same transaction** as the line update. If the insert finds the ID already
there, the event was applied and nothing else happens. If the transaction
fails, both roll back and the redelivery applies the event as if for the first
time.

That is a different idempotency from `PaymentProcessingConsumer`'s, which
needs no table: a duplicate "process this payment" finds the payment past
`AUTHORIZED` and stops. Adding a refund to a total has no state to check. It
only becomes safe once the projection remembers which events it has applied.

### A refund before its payment

Every event of a payment has the payment's ID as its key, so all of them land
on one partition, in the order the relay sent them (B3, and B7 for several
relays). So why does the projection handle a `REFUND_COMPLETED` that comes
before its payment's event?

Because sometimes the order doesn't hold:

- **Retry topics.** A `PAYMENT_COMPLETED` whose projection fails (the database
  blinked) goes to `payment-events-retry-0` and comes back 5s later, after
  the refund event behind it has already been applied.
- **Re-sent by hand.** An operator resets `published_at` on an outbox row
  (B3) to re-send one event. It arrives after the events that followed it.
- **A rebuild from a topic that has lost the start.** If retention dropped
  the payment's event, its refunds are all there is.

Each refund event carries the payment's ID, both accounts and the currency,
so it can **create** the line: the account it belongs to, the refunded amount,
no status. The payment's event **fills in** the status, amount and
`settled_at` when it arrives, and keeps the refund total. A line with no
status isn't settled, so the statement leaves it out until the payment's event
fills it in.

During a rebalance, two instances can both hold one partition for a moment.
`findLine` locks the row (`SELECT ... FOR UPDATE`), so a second event for the
same payment waits rather than overwriting the first one's refund total.

## Query side

```text
application/port/primary/GetStatementUseCase
application/usecase/query/GetStatementQuery, GetStatementResult
application/service/query/GetStatementService
application/port/secondary/StatementRepository        the same port the projection writes through
infrastructure/adapter/primary/web/StatementController, StatementResponse
```

- **Access.** Only the account's holder can read its statement, through the
  same `AccountStateValidator.isHeldBy` check B1 uses, which asks the account
  system. Anyone else gets `403`.
  - An unknown account is held by nobody, so it's a `403` too. The response
    doesn't say whether the account exists.
  - Another customer's *payment* is a `404` (B1), because a `403` would
    confirm that a guessed ID exists. Here the caller names an account number
    they already have. `403` says it's not theirs and says nothing else.
- **Filters.** `status` is `COMPLETED`, `FAILED` or `CANCELLED`, or absent for
  all three. Anything else is a `400`: the statement lists settled payments
  only. `from` and `to` are days (`2026-10-10`), both included, in UTC.
- **Paging.** Newest first, with the payment ID breaking ties, so no line
  shows on two pages or on none. `page` starts at 0. `size` defaults to 20.
- **Page size cap: `400`, not clamped.** `size` above 100 is refused. A
  client that asks for 500 and quietly gets 100 may stop paging, thinking it
  has everything. The `totalLines` in the response would tell it otherwise,
  but only if it checks. A `400` with "size must be between 1 and 100" fails
  the first time anyone tries it.
- **No reference.** Payment events don't carry the payment's reference, and
  the statement is built only from events. B10 adds it to the event schema.

```json
{
  "lines": [
    { "paymentId": "07c87da8-…", "counterpartyAccountId": "ACC-20001", "amount": 250.00, "currency": "EUR",
      "status": "COMPLETED", "refundedAmount": 40.00, "settledAt": "2026-10-10T19:29:25.275744Z" }
  ],
  "page": 0, "size": 20, "totalLines": 6
}
```

`AccessDeniedException`'s message changed from "Source account X is not held
by the caller" to "Account X is not held by the caller", since it now covers
the statement too.

## Rebuild

A read model built only from events can be thrown away and built again by
replaying them. Do that after fixing a projection bug, or after adding a
column the old lines lack:

1. Stop the instances, so the `payment-statement` group has no members.
2. Empty the read model: `TRUNCATE statement_line, statement_processed_event;`
3. Move the group back to the start of the topic:
   ```bash
   kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group payment-statement \
     --reset-offsets --to-earliest --topic payment-events --execute
   ```
4. Start the instances. The consumer replays every event into the empty
   tables.

The write side keeps working the whole time. Only the statement is behind
until the replay catches up.

### Retention: the topic has to hold all of history

A replay can only rebuild what is still in the topic. Kafka's default
retention is 7 days, after which a replay would bring back last week's
payments and nothing older. `payment-events` was created by the broker's
defaults until now. It now has an explicit configuration
(`PaymentEventsTopic`):

```java
TopicBuilder.name("payment-events").config(TopicConfig.RETENTION_MS_CONFIG, "-1")   // kept forever
```

KafkaAdmin creates the topic at start-up with that configuration, and
`spring.kafka.admin.modify-topic-configs=true` brings an existing topic in
line.

- **Why not compaction?** A compacted topic keeps only the latest message per
  key. The key is the payment ID, and a payment's key carries its payment
  event and each refund. Compaction would keep the last refund and drop the
  payment and the others, which is not enough to rebuild a line.
- **The cost.** The topic grows forever. A payment and its events are a few
  hundred bytes, so that is fine for a long time, but not forever. The usual
  answer is a **snapshot**: save the read model (a table dump) along with the
  offset it reflects, and let retention drop what the snapshot covers. A
  rebuild loads the snapshot, then replays from that offset. There is no
  snapshot here (Known shortcuts).

Don't confuse this with `payment.events.retention=7d` (B3). That one is for
outbox rows that have already been published, in the database, and it
doesn't affect the topic.

## Architecture

```text
application/port/primary/ProjectPaymentEventUseCase   new
application/port/primary/GetStatementUseCase          new
application/port/secondary/StatementRepository        new: the read model, StatementLine, StatementPage
application/service/command/ProjectPaymentEventService new: the projection rules
application/service/query/GetStatementService         new: validation, access, bounds
infrastructure/adapter/primary/messaging/
   StatementProjectionConsumer, ReceivedPaymentEvent  new: payment-events -> use case
infrastructure/adapter/primary/web/
   StatementController, StatementResponse             new
infrastructure/adapter/secondary/persistence/
   StatementPersistenceAdapter, StatementLine*        new: JPA
infrastructure/adapter/secondary/messaging/
   PaymentEventsTopic                                 new: retention
```

`StatementLine` isn't a domain entity. It has no invariants and no
behaviour, and nothing about it can be refused: it is a copy of facts that
already happened. So it lives with its port in the application, as
`AccountEnquiryPort.Account` does, and the domain doesn't change. The
existing ArchUnit rules cover the new classes as they are.

## What changed

```text
src/main/resources/db/migration/V9__create_payment_statement.sql   new: statement_line, statement_processed_event
src/main/resources/application.properties                         + spring.kafka.admin.modify-topic-configs
http/payments.http                                                + Bonus 09 requests
src/main/java/com/example/payment/
├── application/
│   ├── exception/AccessDeniedException.java                      "Account X", not "Source account X"
│   ├── port/primary/GetStatementUseCase.java                     new
│   ├── port/primary/ProjectPaymentEventUseCase.java              new
│   ├── port/secondary/StatementRepository.java                   new
│   ├── service/command/ProjectPaymentEventService.java           new
│   ├── service/query/GetStatementService.java                    new
│   └── usecase/query/GetStatementQuery.java, GetStatementResult.java   new
└── infrastructure/adapter/
    ├── primary/messaging/StatementProjectionConsumer.java        new
    ├── primary/messaging/ReceivedPaymentEvent.java               new
    ├── primary/web/StatementController.java, StatementResponse.java    new
    ├── primary/web/PaymentExceptionHandler.java                  comment: 403 covers the statement
    ├── secondary/messaging/PaymentEventsTopic.java               new
    └── secondary/persistence/StatementLineEntity.java,
        StatementLineJpaRepository.java, StatementPersistenceAdapter.java   new
```

## Discuss

- **A read model or a SQL view?** A view over `payment` and `refund` would
  give the same lines, with no events, no consumer, no second copy and no
  delay. While the data lives in this service's database and the query is
  cheap enough, the view is the better choice. A read model starts to pay
  when:
  - the query side needs data from elsewhere: another service's events, or
    the account system's names
  - the query is too slow against tables shaped for writes
  - it has to scale or be stored separately (a search index, a cache)
  - the write tables must be free to change without breaking it

  This statement is here to show the pattern, and with B10's references and
  B11's webhooks to come, it is the start of a query side. A view would be a
  fair answer.
- **Eventual consistency in the UI.** Right after paying, the customer opens
  the statement and the payment isn't there. Ways to handle that:
  - The client shows what it already knows: the 202 and `GET /payments/{id}`
    give the payment as it is right now.
  - Return the time the read model was last updated, so the UI can say "as of
    19:29:25".
  - Have the client wait until the statement has caught up with the payment
    it just made ("read your writes").

  What not to do is have the statement query the write tables "just for the
  latest payments". That brings the coupling back, plus two ways of
  answering one question.

## Known shortcuts

- **No snapshot.** Rebuilds replay from the start of `payment-events`, which
  is therefore kept forever. At volume, snapshot the read model and let
  retention drop what the snapshot covers.
- **Only settled payments, by design.** A payment that is `CREATED`,
  `AUTHORIZED` or `PROCESSING` publishes no event, so it can't appear.
  Payments in progress are on `GET /payments/{id}`.
- **Only payments made from the account.** A line is on the source account's
  statement. Incoming payments aren't shown on the destination account's.
  That would be a second line per payment, plus a decision about whether the
  receiving customer may see the payer's account.
- **A parked event is missing until it's replayed.** An event that fails
  every retry goes to `payment-events-dlt`, and nothing reads that topic
  (Episode 17's shortcut). The statement lacks it until someone replays it or
  rebuilds.
- **No reference on a line.** Events don't carry one yet (B10).
- **The local topic doesn't survive `docker compose down`.** The Kafka
  container has no volume, so its topics go with it. The database keeps
  outbox rows only 7 days after they're published. Locally, a rebuild after a
  `down` brings back only the events published since.
- **Every read asks the account system** who holds the account, as B1's
  checks do. A cached answer would be cheaper and could be stale.

## Try it

```bash
docker compose up -d
./mvnw spring-boot:run
```

The tokens are in [http/payments.http](../../http/payments.http).

1. **A line with its refund.** Create and execute a payment from `ACC-10001`,
   then refund 40.00 of it (Bonus 04 in the `.http` file). Then run Bonus 09's
   first request: the payment is the first line, with `"refundedAmount":
   40.00`.
2. **Access and the cap.** The same request with `otherToken` is a `403`,
   and `size=500` is a `400`.
3. **Retention.** The compose image has no Kafka scripts, so use the full
   image as a client:
   ```bash
   docker run --rm --network host apache/kafka:4.1.1 /opt/kafka/bin/kafka-configs.sh \
     --bootstrap-server localhost:9092 --describe --entity-type topics --entity-name payment-events
   ```
   The output shows `retention.ms=-1`.
4. **A rebuild.** Save the statement (`curl ... > before.json`), stop the
   service, and run:
   ```bash
   docker compose exec postgres psql -U payments -d payments -c "TRUNCATE statement_line, statement_processed_event;"
   docker run --rm --network host apache/kafka:4.1.1 /opt/kafka/bin/kafka-consumer-groups.sh \
     --bootstrap-server localhost:9092 --group payment-statement --reset-offsets --to-earliest \
     --topic payment-events --execute
   ```
   Start the service again and fetch the statement: it matches `before.json`.

## Tests

| Test | Change |
|---|---|
| `ProjectPaymentEventServiceTest` | **new, 6.** With an in-memory statement: a completed payment becomes a line on its source account; failed and cancelled keep their status; completed refunds are summed and failed ones aren't; an event delivered twice counts once; a refund before its payment creates the line, and the payment's event fills it in and keeps the refund. |
| `GetStatementServiceTest` | **new, 6.** The holder's query reaches the repository with both days included; no status means all three settled ones; another customer's account is denied; size 101 is refused and 100 isn't; a status that isn't settled is refused; `from` after `to` is refused. |
| `StatementPersistenceAdapterTest` | **new, 5.** Against PostgreSQL: an event is marked processed once; a line is read back as saved and replaced whole; newest first, paged, with the total; only the account's lines with the status in the range; a line not settled yet is left out. |
| `PaymentEndToEndTest` | **+5.** Create, execute, refund 40 → the statement line shows 40 refunded (awaited). Events published straight to `payment-events`, with one refund sent twice → one line, 30 refunded. Filters and paging. Another customer's account and an unknown one → 403. Size 101, a status that isn't settled, and a malformed date → 400. |
| `AccountStateValidatorTest`, `CreatePaymentServiceTest`, `PaymentEndToEndTest` | The access-denied message is now "Account X is not held by the caller". |

269 tests, passing.
