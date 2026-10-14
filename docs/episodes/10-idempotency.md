# Episode 10 — Idempotency

Git tag: `episode-10-idempotency` · Previous: [Episode 09 — Execute Payment](09-payment-execution.md) · Next: [Episode 11 — Cancel Payment](11-cancel-payment.md)

## Goal

A client sends `POST /payments`, and the connection drops before the response
arrives. Did the payment get created? The client can't know. If it sends the
request again and the first one did get through, the customer now has two
payments.

This episode makes every `POST /payments` carry an **`Idempotency-Key`**:

```http
POST /payments
Idempotency-Key: 6f1c2e0a-8f43-4d8e-9c55-2f7d9a0b1e21
Content-Type: application/json
```

- **No key**: rejected with `400`. A payment request the client can't safely
  retry is not accepted.
- **Same key, same request**: returns the original payment (`201`, same
  `paymentId`). No second payment is created.
- **Same key, different request**: rejected with `422`. Reusing a key for new
  content is a client bug, not a retry.
- **Two requests with the same key at the same moment**: only one payment is
  created. The other request gets that payment, or `409` if the first one
  hasn't finished yet.

## Why payments need this

Networks fail in the worst place: after the server did the work, before the
client heard about it. Timeouts, load balancer retries, a mobile app that
resends when it comes back online, a user who clicks "Pay" twice. They all
turn "send it again" into "pay twice" unless the server can recognize a
retry.

The server can't recognize one on its own. Two identical payments of
250.00 EUR to the same account can be a retry, or two real invoices. Only the
client knows, so the client says it: same key means "this is the same
request".

## Who decides what

```text
Web adapter               Idempotency-Key header -> CreatePaymentCommand.idempotencyKey
    |
    v
CreatePaymentService      seen this key?  same request -> return that payment
    |                                     different    -> 422
    |                     validate, claim the key, save the payment
    |
    +--> IdempotencyPort --> IdempotencyPersistenceAdapter
                             "claim this key, atomically"  (PostgreSQL primary key)
```

- **The application owns the rule.** "A retry gets the original result" is
  payment behaviour, so it lives in `CreatePaymentService`, not in a servlet
  filter. A message consumer in Episode 17 gets it for free by putting the
  message id in the same command field.
- **The adapter owns atomicity.** `IdempotencyPort.claim` promises that of two
  claims of the same key exactly one wins. How is an infrastructure question.
  Here, the answer is PostgreSQL.
- **HTTP stays in the web adapter.** The header name and the `409`/`422`
  statuses are only in `PaymentController` and `PaymentExceptionHandler`.

## What changed

```text
src/main/java/com/example/payment/
├── application/
│   ├── exception/
│   │   ├── IdempotencyKeyInProgressException.java      new
│   │   └── IdempotencyKeyMismatchException.java        new
│   ├── port/secondary/IdempotencyPort.java             new
│   ├── usecase/command/CreatePaymentCommand.java       + idempotencyKey
│   └── service/command/CreatePaymentService.java       + find, claim, replay
└── infrastructure/adapter/
    ├── primary/web/
    │   ├── PaymentController.java                      + Idempotency-Key header
    │   ├── PaymentRequest.java                         toCommand(idempotencyKey)
    │   └── PaymentExceptionHandler.java                + in progress -> 409, mismatch -> 422
    └── secondary/persistence/
        ├── IdempotencyEntity.java                      new
        ├── IdempotencyJpaRepository.java               new
        └── IdempotencyPersistenceAdapter.java          new

src/main/resources/db/migration/
└── V2__create_idempotency_key.sql                      new
```

## How it works

```java
String fingerprint = fingerprint(payment);
Optional<StoredRequest> stored = idempotencyPort.find(key);
if (stored.isPresent()) {
	return replay(stored.get(), fingerprint);        // a retry: never validated or saved again
}

amountLimitValidator.validate(payment.amount());
accountStateValidator.validate(...);

if (!idempotencyPort.claim(key, fingerprint, payment.id())) {
	return replay(idempotencyPort.find(key).orElseThrow(), fingerprint);  // lost the race
}
paymentRepository.save(payment);
```

### The fingerprint: "is this the same request?"

The key alone isn't enough. A client that reuses a key by mistake for a
*different* payment must not get the old one back silently. So the service
stores a SHA-256 fingerprint of the request next to the key and compares it on
every retry.

The fingerprint is built from the value objects, not the raw JSON:
`250`, `250.0` and `250.00` all become `Money(250.00 EUR)`, so they count as
the same request. Field order and whitespace don't matter either.

### The key is required

A key the client may leave out protects only the clients that remember it.
The ones that forget are exactly the ones that create duplicates. So
`PaymentController` declares the header as required, and Spring answers a
request without it with `400` before the application is reached.

`CreatePaymentService` requires it too. The command can also come from
somewhere other than HTTP (a message consumer in Episode 17), and the rule
"no payment without a key" belongs to the application, not to one adapter.

This changes the API: every `POST /payments` from earlier episodes now needs
the header. Each episode's git tag keeps the requests as they were then;
[`http/payments.http`](../../http/payments.http) on this tag has a key on all of them.

### Only successful requests take a key

The key is claimed after validation. A request that fails (unknown account,
account system down) leaves no trace, so the client can fix the problem or
wait and retry with the same key. Storing the failure would turn a temporary
`503` into a permanent one.

### Retries skip validation

A retry is answered from what was stored, *before* validation. The account
system isn't asked again, and an account frozen since the first request can't
turn the original `201` into a `400`. The payment exists; the retry just
reports it.

### Concurrent duplicates: let the database decide

Two requests with the same key can both `find` nothing and both pass
validation. Checking again in Java can't fix that; there is always a gap
between "check" and "write". So the claim is a single SQL statement:

```sql
INSERT INTO idempotency_key (idempotency_key, request_fingerprint, payment_id)
VALUES (:key, :fingerprint, :paymentId)
ON CONFLICT (idempotency_key) DO NOTHING
```

`idempotency_key` is the primary key. If two of these run at once, PostgreSQL
lets one insert and makes the other a no-op. The adapter reports `1` row as
"won" and `0` as "lost". The loser never saves its payment. It reads the
winner's claim and answers with the winner's payment.

Spring Data's `save()` wouldn't do here: for an entity with an assigned id it
runs `SELECT` then `INSERT` *or* `UPDATE`, and an `UPDATE` would quietly
overwrite the winner's row.

### `409` while the first request is still running

The key is claimed *before* the payment is saved. If a duplicate arrives in
between, the claim points at a payment that isn't there yet. The service
answers `409 Conflict`: retry in a moment, and you'll get the payment.

That's also why `idempotency_key.payment_id` has no foreign key to `payment`:
for a short time the claim exists without the payment.

Both together, for two requests with the same key that arrive at the same moment:

```mermaid
%%{init: {"sequence": {"mirrorActors": false}}}%%
sequenceDiagram
    participant CA as Client A
    participant A as CreatePaymentService<br/>(request A)
    participant DB as PostgreSQL
    participant B as CreatePaymentService<br/>(request B)
    participant CB as Client B

    CA->>A: POST /payments, key K
    CB->>B: POST /payments, key K
    A->>DB: find(K)
    DB-->>A: nothing
    B->>DB: find(K)
    DB-->>B: nothing
    Note over A,B: both validate: neither knows about the other
    rect rgba(37, 99, 235, 0.12)
    Note over A,B: the claim: one SQL statement, PostgreSQL picks the winner
    A->>DB: claim K for P1<br/>INSERT … ON CONFLICT DO NOTHING
    DB-->>A: 1 row: A wins
    B->>DB: claim K for P2<br/>INSERT … ON CONFLICT DO NOTHING
    DB-->>B: 0 rows: B lost, P2 is never saved
    end
    B->>DB: find(K)
    DB-->>B: K → P1, not saved yet
    B-->>CB: 409 Conflict, retry in a moment
    A->>DB: save P1
    A-->>CA: 201, P1
    CB->>B: retry, key K
    B->>DB: find(K)
    DB-->>B: K → P1
    B-->>CB: 201, P1: the same payment
```

### `422` for a reused key

| Situation | HTTP |
|---|---|
| new key | `201`, new payment |
| no key | `400` |
| same key, same request | `201`, the original payment |
| same key, different request | `422 Unprocessable Content` |
| same key, first request still running | `409 Conflict` |
| key blank or longer than 255 characters | `400` |

These follow the IETF draft *The Idempotency-Key HTTP Header Field*.

### The replay returns the payment as it is now

A retry returns the stored payment as it is now. If it has been executed
since, the retry shows `COMPLETED`, not `CREATED`. The `paymentId` is what
matters to the client: it's the same payment, not a new one. Replaying the
exact original response body would mean storing HTTP responses, which is a
web-adapter concern and isn't needed yet.

## Known shortcuts

- **If saving the payment fails after the claim, the key is stuck.** Every
  retry with that key gets `409`. Episode 13 (transactions) puts the claim and
  the save in one transaction, so they succeed or fail together.
- **Keys are kept forever.** Real systems expire them after a day or so.
  That's a scheduled `DELETE`, added when the table's size matters.
- **Only `POST /payments` takes a key.** `POST /payments/{id}/execute` is
  already safe to repeat: the second call is `409`, because the lifecycle
  allows only one execution. Two *concurrent* executes are still a race;
  Episode 13 closes it.

## Try it

```bash
./mvnw spring-boot:run
```

In [`http/payments.http`](../../http/payments.http), run the Episode 10
requests: create with a key, send the same request again (same `paymentId`),
change the amount and keep the key (`422`), then leave the key out (`400`).

## Tests

| Test | What it proves |
|---|---|
| `application/service/command/CreatePaymentServiceTest` | **+8 cases.** A missing, blank or overlong key is rejected before the account system is asked. Same key and same request returns the original payment without asking the account system again; `250` and `250.00` count as the same request; a different request is `IdempotencyKeyMismatchException`; losing the claim returns the winner's payment without saving; a claimed but unsaved key is `IdempotencyKeyInProgressException`; a rejected request doesn't use up its key. |
| `infrastructure/adapter/secondary/persistence/IdempotencyPersistenceAdapterTest` | **New.** Against PostgreSQL: the first claim wins and reads back; a second claim of the same key returns false and leaves the first untouched. |
| `infrastructure/adapter/primary/web/PaymentControllerPortTest` | **+2 mappings.** The header becomes `idempotencyKey`; in progress is `409`, mismatch is `422`. |
| `infrastructure/adapter/primary/web/PaymentControllerTest` | **+4 cases.** End to end: no header is `400` and stores nothing; a retry returns the same `paymentId` and stores one row; a reused key with a different body is `422`; eight concurrent identical requests create exactly one payment. |
| Everything else | Unchanged, except that every create now sends a key. |
