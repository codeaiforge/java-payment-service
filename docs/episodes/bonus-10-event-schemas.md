# Bonus Episode 10 — Versioned Event Schemas

Git tag: `bonus-10-event-schemas` · Builds on: [Bonus Episode 09 — Payment Statement](bonus-09-payment-statement.md)

## Goal

`payment-events` has consumers that aren't this service's write side: B9's
statement, and, in the story, a ledger team and a notification team. A
change to the event must never break a consumer that hasn't been updated,
and a change that would break one must fail the build, not production.

The business change that brings this up: the statement must show each
payment's **reference**, so events must carry it.

```json
{ "paymentId": "07c87da8-…", "counterpartyAccountId": "ACC-20001", "amount": 250.00, "currency": "EUR",
  "reference": "Invoice 12345", "status": "COMPLETED", "refundedAmount": 40.00, "settledAt": "…" }
```

That is the only business change. Payment and refund rules don't change.

## From JSON to a contract

Until now each event was JSON, and its shape was whatever the Java record
`PaymentEventMessage` happened to be. The contract existed only as a class in
the publisher. Nothing checked it, so any edit to that class was a change to
the contract:

- **Rename `amount` to `value`.** It compiles and the publisher's tests pass.
  Every consumer now reads `amount` as missing. B9's statement fails on each
  event and parks them all in the dead-letter topic. A consumer with lenient
  parsing reads `null` and records a zero.
- **Add a field.** It's fine only because consumers happen to ignore unknown
  fields. B3's doc noted this for `refundId`: "adding it broke no consumer
  that ignores unknown fields". That was luck, not a rule.

So the contract is now a schema, kept apart from any class:
[`src/main/avro/PaymentEvent.avsc`](../../src/main/avro/PaymentEvent.avsc).
Messages are Avro, and every version of the schema goes into a **schema
registry**, which refuses a version that would break consumers.

### Avro and a registry

An Avro message holds only values, in field order, with no field names. A
reader needs the writer's schema to decode it. Each message therefore starts
with a magic byte and a 4-byte ID, and the ID points at the writer's schema
in the registry. The reader fetches that schema once and caches it. Avro then
**resolves** the writer's schema against the reader's own:

- a field the writer has and the reader doesn't is skipped
- a field the reader has and the writer doesn't gets the reader's default
- a field with no default that the writer didn't write is an error

Those resolution rules define what "compatible" means, and the registry
applies them to every new version.

### Apicurio Registry, not Confluent's

Both speak the same idea. This project uses
[Apicurio Registry](https://www.apicur.io/registry/) 3.3:

- **Its storage is in-memory by default (or PostgreSQL).** Confluent Schema
  Registry stores schemas in a Kafka topic, so a test needs it networked to
  the Kafka container. Apicurio is one more independent container, locally
  and in Testcontainers.
- **Its serializers are on Maven Central, Apache 2.0 like the registry.**
  Confluent's serializers come from Confluent's own Maven repository, one
  more repository in every build.
- **It has a Confluent-compatible API** (`/apis/ccompat/v7`), so tools built
  for Confluent's registry work against it too.

A team already running Confluent Platform would use Confluent's. Nothing in
the domain or the application layer would change: only the serializer class
and the registry URL.

### Compatibility: `FULL_TRANSITIVE`

The registry's compatibility rule decides which side may upgrade first:

| Mode | A new schema must... | So upgrade first |
|---|---|---|
| `BACKWARD` | read data written with the previous schema | consumers |
| `FORWARD` | be readable by the previous schema | the producer |
| `FULL` | both | either, in any order |
| `…_TRANSITIVE` | the same, against **every** earlier version, not only the last | |

Two things are true here, and together they mean `FULL_TRANSITIVE`:

- **The producer upgrades first, and consumers whenever they like.** The
  ledger and notification teams deploy on their own schedules. The day this
  service starts publishing `reference`, they are still on the old schema,
  and they must keep working. That requires `FORWARD`.
- **The statement replays everything with the newest schema.** B9 rebuilds
  the statement by replaying `payment-events` from the start, and the topic
  keeps every event forever. The current consumer has to read events written
  with v1, v2 and every version since. That requires `BACKWARD`, and
  transitively, against all of them.

```yaml
# docker-compose.yml
APICURIO_RULES_GLOBAL_COMPATIBILITY: FULL_TRANSITIVE
```

## The schema

```text
eventId               string
type                  enum PaymentEventType { PAYMENT_COMPLETED, … REFUND_FAILED }
paymentId             string
refundId              ["null", "string"], default null
sourceAccountId       string
destinationAccountId  string
amount                decimal(19, 2) as bytes
currency              string
occurredAt            timestamp-micros
reference             ["null", "string"], default null      v2
```

Each choice is a contract decision, and consumers will hold us to it:

- **`amount` is `decimal(19, 2)`**, exactly like the database column. A float
  would round money. The scale is part of the contract: a currency with three
  minor units would need a new field, which Currency's `SHORTCUT:` already
  anticipates.
- **`type` is an enum, `currency` a string.** A consumer switches on `type`,
  so a new event type should be a deliberate contract change. An old reader's
  enum has no symbol for it, and the contract test fails (below). Currencies,
  on the other hand, will be added, and that must not break anyone. A string
  makes "a code you don't know" the consumer's problem, which is where it
  belongs.
- **IDs are strings.** That is what they were in JSON, and every language
  reads them.
- **`occurredAt` is microseconds.** That is what PostgreSQL keeps, so an
  event read back from the outbox survives the trip unchanged.

`avro-maven-plugin` generates `com.example.payment.events.PaymentEvent` and
`PaymentEventType` from the schema before compiling. These are the wire
format, like a JPA entity is the table. A new ArchUnit rule keeps them inside
the two messaging adapters: the domain's `PaymentEvent` stays a plain record,
and the adapters map one to the other.

## The change: `reference`, added the safe way

```json
{"name": "reference", "type": ["null", "string"], "default": null}
```

**Optional, with a default.** That makes the change compatible in both
directions:

- An old consumer reading a v2 event skips `reference`, a field its schema
  doesn't have.
- The current consumer reading a v1 event has no value for `reference`, so it
  takes the default, `null`.

The same field added as plain `"string"`, with no default, would be
backward-incompatible: there is nothing to fill in for v1 events. Renaming
`amount` would be incompatible both ways, because a rename is a removed field
plus a new one. The contract test checks both cases, and the end-to-end test
shows the registry refusing the rename.

"v1" here is the shape the JSON messages had, written as an Avro schema. It
is the version any existing consumer was built against, kept in
[`src/test/resources/schemas/PaymentEvent/v1.avsc`](../../src/test/resources/schemas/PaymentEvent/v1.avsc).

### Down through the layers

- **Domain.** `PaymentEvent` gets `PaymentReference reference`. Both
  factories fill it in from the payment, refund events included. It can be
  `null`, but only on an event read back from the topic that was published
  before events carried it.
- **Outbox.** `V10__add_event_reference.sql` adds a nullable `reference`
  column to `payment_outbox`. Rows written before the migration have none and
  are published without one. The outbox stores the event, not the message
  (B3), so the column follows the event.
- **Statement.** The same migration adds `statement_line.reference`. The
  projection takes the reference from whichever event has one, and an event
  without one never erases it. `GetStatementResult` and the response gain the
  field. For lines built before this episode it stays `null` until the
  statement is rebuilt, and even then only events published from now on carry
  a reference.

### Personal-ish free text

The reference is whatever the payer typed: "Invoice 12345", or a name, or a
reason for paying someone. Episode 15 kept it out of logs and metrics, B5 out
of spans, and B6 out of exported log records. Events are different: B3's rule
is that an event carries what its consumers need, and the statement needs the
reference. So:

- **in the event:** yes, because consumers are services we authorized to read
  `payment-events`
- **in logs, metrics, spans and log records:** still never

The privacy tests from Episode 15, B5 and B6 could finish before the event
was even sent. They now wait until the event has travelled the whole way (sent,
consumed, and applied to the statement) and then check every span and every
exported log record again. Nothing logs or traces the message body, so they
pass unchanged.

## Wiring

```text
PaymentEventRelay --PaymentEventMessage.from(domain event)--> Avro PaymentEvent
   --KafkaTemplate--> DelegatingByTypeSerializer --AvroKafkaSerializer--> registry + payment-events
payment-events --ErrorHandlingDeserializer(AvroKafkaDeserializer, specific reader)--> Avro PaymentEvent
   --StatementProjectionConsumer.toEvent--> domain PaymentEvent --> ProjectPaymentEventUseCase
```

- **One producer, two formats** (`config/MessagingConfiguration`).
  `payment-processing` stays JSON (see Known shortcuts). Each value picks its
  serializer by type: `byte[]` passes through, Avro records go to the
  registry's serializer, and everything else is JSON. One producer matters
  because Spring's retry topics re-send whatever a listener failed on,
  through the application's one `KafkaTemplate`. A JSON-only template would
  turn a failed Avro event into JSON on `payment-events-retry-0`, which the
  listener could never read back.
- **The consumer reads into the generated class.** It overrides only its own
  deserializer (`@KafkaListener(properties = …)`), so the `payment-processing`
  listener is untouched. `ReceivedPaymentEvent` is gone. The consumer's copy
  of the contract used to be a record; now it is the schema, resolved by Avro.
- **Avro is told which classes to trust.** Avro 1.12 only creates classes
  from trusted packages, because a schema naming an arbitrary class was a
  remote-code-execution hole (CVE-2024-47561). `MessagingConfiguration`
  trusts exactly the two generated classes.
- **The relay registers the schema on its first send** (auto-register),
  dereferenced, so the registry holds one self-contained schema per version.
  Left alone, the serializer registers the enum as a second artifact
  referenced from the first, and every tool reading the schema then has to
  resolve the reference.
- **The relay stops on any failure.** If the registry is down, or refuses the
  schema, `send()` throws the registry's exception directly, not wrapped in a
  `KafkaException`. The relay now stops the batch on any `RuntimeException`.
  Nothing is lost: the rows stay unpublished, and the `payment_events_pending`
  gauge (B3) climbs. A schema refused in production means the
  build's check was skipped, and the events wait until a compatible version
  ships.

### Not done: switching an existing topic from JSON to Avro

Changing the serialization format is the one change no registry can check.
Every message already on `payment-events` is JSON, and the Avro deserializer
can't read it. A rebuild that replays from the start would park every old
event in the dead-letter topic. Locally that's fine: the Kafka container has
no volume, so `docker compose down` starts the topic over. A real system
would either:

- **re-publish history in the new format**, from the outbox or the write
  tables, into a fresh topic, and move every consumer over at once. A format
  change is the one case where a new topic is the right tool.
- **read both formats for a while.** An Avro message starts with the byte
  `0` and a JSON one with `{`. A deserializer can pick by the first byte
  until the old messages have aged out, which never happens here, because
  retention is forever.

## Contract tests

Three layers, from fastest to most real:

1. **The build: `PaymentEventSchemaTest`.** No Docker. Avro's own
   `SchemaCompatibility` checks every schema in `src/main/avro` against every
   released version in `src/test/resources/schemas/{name}/`, in both
   directions. That is `FULL_TRANSITIVE`, checked before anything is
   deployed. Releasing a version means copying its schema into that
   directory, unchanged. Three more tests show the check failing: a required
   `reference`, a renamed `amount`, and a new event type.
2. **The consumer: `StatementProjectionConsumerTest`.** No Docker. Bytes
   written with v1 (no reference) and with the current schema are both read
   the way the deserializer reads them, and mapped to the domain event. The
   old one comes out with no reference, the new one with its reference.
3. **End to end, with a real registry** (`PaymentEndToEndTest`, Apicurio in
   Testcontainers):
   - Every check on what the relay publishes now reads the topic **as a
     consumer built on v1 would** (`ReleasedSchemaV1`), so each one is also a
     check that an old consumer reads a new event. The reference is simply
     not there for it.
   - The registry holds exactly the schema in `src/main/avro`, and refuses a
     version with `amount` renamed (`RuleViolationException`, `400`).
   - The statement line shows the payment's reference.

## Architecture

```text
src/main/avro/PaymentEvent.avsc                           new: the contract
com.example.payment.events.PaymentEvent, PaymentEventType generated; only the messaging adapters see them (ArchUnit)
config/MessagingConfiguration                             new: serializer by type, trusted Avro classes
infrastructure/adapter/secondary/messaging/
   PaymentEventMessage                                    domain event -> Avro record (was: the JSON record)
   PaymentEventRelay                                      sends Avro; stops on any failure
infrastructure/adapter/primary/messaging/
   StatementProjectionConsumer                            Avro record -> domain event
   ReceivedPaymentEvent                                   removed
```

The domain gains one field. It doesn't know about Avro, the registry or
Kafka, and the existing rules still check that. The application gains the
field on `StatementLine` and `GetStatementResult.Line`, and nothing else.

## What changed

```text
pom.xml                                                         + apicurio-registry-avro-serde-kafka (no Vert.x), avro-maven-plugin
docker-compose.yml                                              + schema-registry (Apicurio, FULL_TRANSITIVE) on :8085
src/main/avro/PaymentEvent.avsc                                 new
src/main/resources/application.properties                      + registry URL, auto-register, dereference; JSON serializer moved to config
src/main/resources/db/migration/V10__add_event_reference.sql   new: payment_outbox.reference, statement_line.reference
http/payments.http                                              + Bonus 10 requests
src/main/java/com/example/payment/
├── domain/event/PaymentEvent.java                              + reference
├── application/
│   ├── port/secondary/StatementRepository.java                 + StatementLine.reference
│   ├── service/command/ProjectPaymentEventService.java         reference from whichever event has one
│   ├── service/query/GetStatementService.java                  + reference
│   └── usecase/query/GetStatementResult.java                   + reference
├── config/MessagingConfiguration.java                          new
└── infrastructure/adapter/
    ├── primary/messaging/StatementProjectionConsumer.java      reads Avro, maps it
    ├── primary/messaging/ReceivedPaymentEvent.java             removed
    ├── primary/web/StatementResponse.java                      + reference
    ├── secondary/messaging/PaymentEventMessage.java            maps to the Avro record
    ├── secondary/messaging/PaymentEventRelay.java              sends Avro; stops on any RuntimeException
    └── secondary/persistence/Outbox*, StatementLine*,
        StatementPersistenceAdapter.java                        + reference
src/test/resources/schemas/PaymentEvent/v1.avsc                 new: the released version
```

## Discuss

- **Evolution rules**, which follow from Avro's resolution:
  - Add a field only as optional, with a default.
  - Never rename. Add the new field, fill both, and retire the old one with
    a deprecation period. Avro's `aliases` help readers, but not readers
    built on an old schema.
  - Never remove a field without a default, and never reuse a removed
    field's name for something else. An old event replayed years later
    would put its value into the new meaning.
  - Never change a field's type, except where Avro promotes it (`int` to
    `long`, `string` to `bytes`).
- **Upgrade order.** Under `BACKWARD`, consumers deploy first: a producer
  that ships first can publish what old consumers can't read. Under
  `FORWARD`, the producer deploys first. Under `FULL`, order doesn't matter,
  which is why it suits one producer with many teams reading. The price is
  that fewer changes qualify: only optional fields with defaults, which is
  the discipline above anyway.
- **Why not `payment-events-v2`?** A version in the topic name means a second
  topic for every change: every consumer moves topics, the producer
  dual-writes during the move, and per-payment ordering across the two topics
  is lost. With the version in the schema, compatible changes need none of
  that, and the registry proves they are compatible. A new topic is for what
  the registry can't make compatible: a different format (JSON to Avro,
  above), or a different meaning of the same event.

## Known shortcuts

- **`payment-processing` has no schema.** It is internal: this service
  produces it and this service consumes it, from one codebase, deployed
  together. A change to `PaymentProcessingMessage` is a refactoring, checked
  by the compiler and the end-to-end tests. It gets a schema the day a second
  service consumes it, or the producer and consumer start deploying
  separately.
- **No CloudEvents envelope.** Event metadata (`eventId`, `type`,
  `occurredAt`) sits in the payload with the data. The
  [CloudEvents](https://cloudevents.io/) spec would standardise it in headers
  (`ce_id`, `ce_type`, `ce_time`), so generic tooling can route events
  without knowing their schema. Worth it when events cross teams' tooling,
  not only teams' code.
- **The relay registers its own schema.** That is fine with one producer. In
  a larger setup, CI registers schemas (after `PaymentEventSchemaTest` passes)
  and producers only look them up (`auto-register=false`). Then production
  can't register anything.
- **The local registry is in memory.** A restart forgets every schema, while
  the messages on `payment-events` still name the old IDs. After restarting
  it, start Kafka over too (`docker compose down`). A real registry stores
  its schemas in PostgreSQL.
- **No JSON-to-Avro cutover** for messages already on the topic (above).
- **The releases directory is maintained by hand.** Releasing v2 means
  copying `PaymentEvent.avsc` to `src/test/resources/schemas/PaymentEvent/v2.avsc`.
  A release pipeline would do it, or would check against the production
  registry instead.

## Try it

```bash
docker compose down        # starts payment-events over: old JSON messages can't be read as Avro
docker compose up -d
./mvnw spring-boot:run
```

1. **A reference on the statement.** Create, execute and refund a payment
   from `ACC-10001` (as in Bonus 09). Bonus 10's first request returns the
   statement, and the line shows `"reference"`.
2. **The registered schema.** Bonus 10's second request lists the versions of
   `payment-events-value`. The third shows the latest one: the schema in
   `src/main/avro`.
3. **A breaking change refused.** Rename `amount` and try to register it:
   ```bash
   curl -s localhost:8085/apis/registry/v3/groups/default/artifacts/payment-events-value/versions/branch=latest/content \
     | sed 's/"amount"/"value"/' \
     | jq -Rs '{content: {content: ., contentType: "application/json"}}' \
     | curl -s -XPOST localhost:8085/apis/registry/v3/groups/default/artifacts/payment-events-value/versions \
         -H 'Content-Type: application/json' -d @- | jq .title
   ```
   The answer is `"Incompatible artifact: payment-events-value [AVRO] …"`.
4. **The same change in the build.** Rename `amount` in
   `src/main/avro/PaymentEvent.avsc` and run
   `./mvnw test -Dtest=PaymentEventSchemaTest`. It fails, naming the file and
   v1. Put it back.

## Tests

| Test | Change |
|---|---|
| `PaymentEventSchemaTest` | **new, 4.** Every schema in `src/main/avro` is compatible both ways with every released version. A required `reference`, a renamed `amount` and a new event type are each incompatible. |
| `StatementProjectionConsumerTest` | **new, 2.** An event written with v1 is read with no reference; one written now is read with its reference. |
| `ProjectPaymentEventServiceTest` | **+2.** The reference comes from whichever event has one, a refund arriving first included. An event without one keeps the line's. |
| `PaymentEventTest` | Both factories carry the payment's reference. |
| `StatementPersistenceAdapterTest`, `GetStatementServiceTest` | The reference is stored, read back and returned. |
| `ArchitectureTest` | **+1.** The generated classes are used only in the messaging adapters. |
| `PaymentEndToEndTest` | **+1.** The registry holds the current schema and refuses `amount` renamed. Events on `payment-events` are read with v1, as an old consumer would, and events published straight to the topic are Avro. The statement line shows the reference. The span and log record checks wait until the event has been sent and applied before checking. |
| `MultipleInstancesTest` | Reads `payment-events` as Avro, with its own registry container. |

279 tests, passing.
