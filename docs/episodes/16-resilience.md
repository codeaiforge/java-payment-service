# Episode 16 — Resilience

Git tag: `episode-16-resilience` · Previous: [Episode 15 — Observability](15-observability.md) · Next: [Episode 17 — Asynchronous Processing](17-async-processing.md)

## Goal

Every external call so far has assumed the other system answers, and
answers quickly. In practice they don't always. A system stops responding
and holds our threads for a minute. One slow dependency turns into an
outage of ours. A brief blip fails a payment that would have worked a
second later.

This episode gives every adapter in `infrastructure/adapter/secondary/feign`
three defences:

- **Timeouts.** Every call has a deadline.
- **Retries.** A failed call is tried again, but only when trying again is safe.
- **Circuit breakers.** When a system keeps failing, stop calling it for a while.

It also replaces the last stub. Payment execution now goes over HTTP to an
external payment system, and that call is the one we must **not** retry.

## Timeouts

Feign's defaults are 10 seconds to connect and 60 seconds to read. A
system that accepts connections and never answers would hold one of our
request threads for a minute per payment.

```properties
spring.cloud.openfeign.client.config.default.connect-timeout=1000
spring.cloud.openfeign.client.config.default.read-timeout=2000
spring.cloud.openfeign.client.config.payment-system.read-timeout=5000
```

The payment system gets longer to read. Moving money is slower than
answering a question, and, as the next sections show, every timeout on that
call costs us more than a timeout on the others.

## Retries: questions, not instructions

Three of the four external calls ask a question:

| Adapter | Call | Asking twice |
|---|---|---|
| `AccountEnquiryFeignAdapter` | `GET /accounts/{id}` | harmless: a read |
| `AuthorizationAdapter` | `POST /authorizations` | harmless: a decision, nothing changes |
| `LimitAdapter` | `POST /limit-checks` | harmless: a check, nothing reserved |
| `PaymentExecutionAdapter` | `POST /payment-orders` | **could pay twice** |

The first three get a Resilience4j `Retry`: up to 3 attempts, waiting 200 ms
and then 400 ms between them.

```properties
resilience4j.retry.configs.default.max-attempts=3
resilience4j.retry.configs.default.wait-duration=200ms
resilience4j.retry.configs.default.enable-exponential-backoff=true
resilience4j.retry.configs.default.exponential-backoff-multiplier=2
resilience4j.retry.configs.default.retry-exceptions=feign.RetryableException,feign.FeignException$FeignServerException
```

Only failures that might go away are retried. `RetryableException` is how
Feign reports no answer at all (connection refused, timeout).
`FeignServerException` is any 5xx. A 4xx is not retried: the same request
would be refused the same way. A 404 from the account system is not a
failure at all. It is the answer "no such account".

## Circuit breakers

A retry helps with a blip. It makes a real outage worse: every payment now
waits for three timeouts instead of one, and the struggling system gets
three times the traffic.

A circuit breaker watches the last 10 calls to a system. Once at least half
of them have failed (and at least 5 were made), it **opens**: for the next
10 seconds calls fail at once, without touching the network. Then it lets 2
trial calls through. If they succeed it closes; if not, it opens again.

```text
CLOSED --(>= 50% of last 10 failed)--> OPEN --(10s)--> HALF_OPEN --(2 ok)--> CLOSED
                                         ^                 |
                                         +----(failure)----+
```

There is one breaker per external system, named after it: `account-system`,
`authorization-system`, `limit-system`, `payment-system`. A failing limit
system doesn't stop account lookups.

Each attempt counts towards the breaker. Once it is open, nothing is retried:

```java
retry.executeSupplier(() -> circuitBreaker.executeSupplier(
        () -> accountClient.getAccount(accountId.value())));
```

An open breaker throws `CallNotPermittedException`, which the adapter turns
into the same application exception as an outage (`AccountUnavailableException`
or `ExternalSystemUnavailableException`, so `503`). To the caller, "down"
and "we stopped asking because it's down" are the same thing.

Resilience4j publishes its own metrics through Micrometer, so they are
already in Prometheus:

```promql
resilience4j_circuitbreaker_state{state="open"}
resilience4j_retry_calls_total
```

### Why not annotations?

Resilience4j also offers `@Retry` and `@CircuitBreaker` annotations. They
need Spring AOP and make the order of the two easy to get wrong. Two
explicit lines in each adapter show exactly what wraps what, and a test
can see it.

## Payment execution: timeout ≠ failed

Since Episode 09, `StubPaymentExecutionAdapter` answered instantly and
never failed. It is gone. `PaymentExecutionAdapter` sends the payment to the
external payment system:

```http
POST /payment-orders
Idempotency-Key: 6f1c...        <- the payment ID
{ "debtorAccount": "ACC-10001", "creditorAccount": "ACC-20001",
  "amount": 250.00, "currency": "EUR", "reference": "Invoice 16016" }

200 { "status": "SETTLED" }     or     200 { "status": "REJECTED" }
```

Now consider the read timeout. We sent the payment and waited 5 seconds
without an answer. What happened?

```text
request timeout
        ≠
payment definitely failed
```

Maybe the request never arrived. Maybe it arrived, the money moved, and the
response was lost on the way back. From our side the two look exactly the
same.

So the port gets a third outcome:

```java
enum Outcome {
    EXECUTED,   // the money moved
    REJECTED,   // the payment system refused it
    UNKNOWN     // no answer: it may or may not have gone through
}
```

The adapter returns `UNKNOWN` for a timeout, a dropped connection, any HTTP
error, a `status` it doesn't recognise, and an open circuit breaker. It
never throws, and it never retries.

| What we'd be tempted to do | What goes wrong |
|---|---|
| Retry the call | If the first one went through, the customer pays twice. |
| Mark the payment `FAILED` | The money may have moved. The customer sees "failed" and pays again another way. |
| Mark it `COMPLETED` | It may not have moved. |

`ExecutePaymentService` does none of those:

```java
case UNKNOWN -> {
    log.warn("Payment {} left PROCESSING: payment system outcome unknown", payment.id());
    return payment;
}
```

The payment stays `PROCESSING`, and `POST /execute` returns `200` with
`"status": "PROCESSING"`.

The whole call, with the two cases we can't tell apart:

```mermaid
%%{init: {"sequence": {"mirrorActors": false}}}%%
sequenceDiagram
    participant C as Client
    participant S as ExecutePaymentService
    participant DB as PostgreSQL
    participant PS as Payment system

    C->>S: POST /payments/{id}/execute
    S->>DB: commit AUTHORIZED → PROCESSING
    Note over S,PS: committed before the call: PROCESSING means "we may have sent this"
    alt the order never arrives
        S-xPS: POST /payment-orders, Idempotency-Key: payment ID
    else the money moves, the answer is lost
        S->>PS: POST /payment-orders, Idempotency-Key: payment ID
        PS->>PS: money moves
        PS--xS: SETTLED, lost on the way back
    end
    rect rgba(217, 119, 6, 0.15)
    Note over S,PS: 5 s read timeout. From here the two cases look the same: outcome UNKNOWN
    Note over S,PS: no retry, not FAILED, not COMPLETED: the payment stays PROCESSING
    end
    S-->>C: 200, "status": "PROCESSING"
    C->>S: POST /payments/{id}/execute again
    S-->>C: 409: the domain refuses PROCESSING → PROCESSING, nothing is sent twice
```

### Connecting it to earlier episodes

This is where earlier episodes come together.

- **Payment status (09, 13).** `PROCESSING` was committed *before* the call
  went out. It already meant "we may have sent this". `UNKNOWN` only means
  we leave it that way. The domain refuses `PROCESSING -> PROCESSING`, so a
  client that calls `execute` again gets `409` and nothing is sent twice.
- **Audit (13).** The trail ends at `AUTHORIZED -> PROCESSING` with a
  timestamp, and has no `PROCESSING -> ...` row. That is exactly the record
  of "sent at 10:42:13, outcome never heard". The log line and the
  `external.payment.errors{system="execution"}` counter carry the same fact.
- **Idempotency (10).** Episode 10 protected *us* from clients that retry.
  Here we are the client. Each payment order carries `Idempotency-Key: <payment ID>`,
  so whoever asks the payment system again later is asking about this
  payment, and the payment system can't pay it twice.
- **Reconciliation.** Resolving a `PROCESSING` payment means asking the
  payment system "what happened to payment 6f1c...?" and applying the answer:
  `complete()` or `fail()`, each with its audit row. With the idempotency
  key, re-sending the same order is a safe way to ask. The building blocks
  are all here. The job that finds stuck payments and does this runs after
  the request has finished, which is Episode 17's subject. See Known shortcuts.

## What changed

```text
pom.xml                                                        + resilience4j-spring-boot4 (+ its BOM)
compose.yaml                                                   comment: WireMock also plays the payment system
wiremock/mappings/payment-orders.json                          new: SETTLED, REJECT..., TIMEOUT...
src/main/resources/application.properties                      + payment-system.url, timeouts, retry, circuit breaker
src/main/java/com/example/payment/
├── application/
│   ├── port/secondary/PaymentExecutionPort.java               + Outcome.UNKNOWN
│   └── service/command/ExecutePaymentService.java             UNKNOWN leaves the payment PROCESSING
└── infrastructure/adapter/secondary/
    ├── feign/
    │   ├── AccountEnquiryFeignAdapter.java                    + Retry, CircuitBreaker
    │   ├── AuthorizationAdapter.java                          + Retry, CircuitBreaker
    │   ├── LimitAdapter.java                                  + Retry, CircuitBreaker
    │   ├── PaymentExecutionClient.java                        new
    │   └── PaymentExecutionAdapter.java                       new: CircuitBreaker, no Retry
    └── stub/
        └── StubPaymentExecutionAdapter.java                   deleted
```

The domain is unchanged. `application` gained one enum constant and one
`case`; it still knows nothing about Feign or Resilience4j, and
`ArchitectureTest` still passes.

### The Resilience4j BOM

Spring Cloud's BOM pins the Resilience4j modules at 2.3.0, which has no
Spring Boot 4 support. `resilience4j-bom` is imported first, so every
Resilience4j module is 2.4.0.

## Known shortcuts

- **No reconciliation job.** Payments left `PROCESSING` stay there until
  someone asks the payment system. The adapter, the idempotency key and the
  audit trail are ready for it. Episode 17 is about work that runs after the
  request.
- **An open breaker on execution is also `UNKNOWN`.** In that case we know
  nothing was sent, so in principle the payment could go back to
  `AUTHORIZED` and be tried again. That needs a new domain transition, and
  reconciliation would find "never received" anyway.
- **`200` with `PROCESSING`, not `202`.** As in Episode 09, the status field
  says how it went. `202 Accepted` would say it louder.
- **Worst case for a question is about 7 seconds.** Three 2-second timeouts
  plus the waits. An outage longer than a few calls opens the breaker, and
  then it is immediate.
- **No bulkhead or rate limiter.** Timeouts already bound how long a thread
  can wait. Add a bulkhead if one slow system starts starving the others.

## Try it

```bash
./mvnw spring-boot:run
```

Send the Episode 16 requests in [http/payments.http](../../http/payments.http):

1. A payment with reference `TIMEOUT ...`. The fake payment system settles it
   but answers after 7 seconds. We stop waiting at 5. `execute` returns
   `200` with `"status": "PROCESSING"`, and the console logs
   `outcome unknown`. Executing again is `409`.
2. Stop the fake systems with `docker compose stop account-system` and create a
   few payments. The first ones take a moment (three attempts each) and
   return `503`. After a few, the breaker opens and `503` comes back at once.
   In Prometheus: `resilience4j_circuitbreaker_state{name="account-system",state="open"}` is `1`.
   `docker compose start account-system`; within about 10 seconds it closes again.

## Tests

| Test | What it proves |
|---|---|
| `PaymentExecutionAdapterTest` | **New.** The payment ID is sent as `Idempotency-Key`. `SETTLED`/`REJECTED` map to `EXECUTED`/`REJECTED`. A timeout, a `503` and an unrecognised status are all `UNKNOWN`, and **exactly one** request is sent. An open breaker sends nothing. |
| `AccountEnquiryFeignAdapterTest` | **3 new.** A `503` followed by `200` succeeds on the second attempt. A `404` is not retried and doesn't trip the breaker. After six failed calls the breaker opens and the next lookup never reaches the account system. The `503` case now checks all three attempts were made. |
| `AuthorizationAndLimitAdapterTest` | **1 new.** A failed authorization is retried. The outage case checks three attempts. |
| `ExecutePaymentServiceTest` | **1 new.** `UNKNOWN` leaves the payment `PROCESSING`, with no third audit row. |
| `PaymentControllerTest` | **1 new.** End to end through the real adapter: the `TIMEOUT` payment ends `PROCESSING`, a second execute is `409`, and the payment system saw one request. The `REJECT` case now goes through WireMock, not the stub. Prometheus shows the breaker state. |
| `PaymentTransactionsTest` | **Changed.** Points the payment system at WireMock. |
