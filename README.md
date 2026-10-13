# Java Payment Service

[![CI](https://github.com/dsofianos78/java-payment-service/actions/workflows/ci.yml/badge.svg)](https://github.com/dsofianos78/java-payment-service/actions/workflows/ci.yml)
![Java 25](https://img.shields.io/badge/Java-25-blue)
![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-brightgreen)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A payment service for a fictional bank, built in 27 tagged steps. Every decision a bank
reviewer will question (idempotency, unknown outcomes, reconciliation, the outbox,
running several instances) is made in the commit that needs it, and explained.

No real bank, customer data, API contract or credential appears anywhere in this
repository.

> Clean Architecture is not a package structure. The package structure is the visible
> result of boundaries that protect business rules and isolate change.

## Who it's for

Java and Spring developers who know the basics and want to see how a service that moves
money is designed, and why. Also architects who want a reference for hexagonal
architecture driven by requirements rather than by a template.

## What you'll learn

- Hexagonal architecture that appears when a requirement needs it, not before (01–05, 20)
- Idempotent creation with a database-enforced claim (10)
- Unknown outcomes: why a timeout is never "failed" (16), and how reconciliation settles them (B2)
- Short transactions that never span an external call, with an audit trail that can't disagree with the payment (13)
- A transactional outbox: at-least-once events (B3)
- Partial refunds that can't over-refund, even under concurrency (B4)
- Architecture rules enforced by ArchUnit tests (14)
- Traces, metrics and logs linked end to end (15, B5, B6)
- Safe on several instances: `FOR UPDATE SKIP LOCKED` and coordinated schedulers (B7)
- A read model built from the service's own events, and rebuilt by replaying them (B9)
- Event contracts that evolve without breaking consumers: Avro, a schema registry, compatibility checked in the build (B10)
- Calling customers' URLs safely: signed webhooks, durable retries, and no way into our own network (B11)
- Rate limits per customer that hold across instances, with token buckets in PostgreSQL (B12)
- Running on Kubernetes: probes that don't restart pods because the database is down, graceful shutdown, and
  migrations that work with the previous release's code (B13)

## The final system

### Payment lifecycle

The aggregate, `Payment`, refuses every move not drawn here.

```mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> AUTHORIZED
    AUTHORIZED --> PROCESSING
    PROCESSING --> COMPLETED
    PROCESSING --> FAILED
    CREATED --> CANCELLED
    AUTHORIZED --> CANCELLED
    COMPLETED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]
    note right of PROCESSING
        If the payment system's answer is lost,
        the payment stays PROCESSING: the money may have moved.
        Reconciliation (B2) asks again and settles it.
    end note
```

### Ports and adapters

```mermaid
flowchart LR
    subgraph primary["Primary adapters"]
        web["RateLimitFilter →<br/>PaymentController · StatementController<br/>WebhookController (REST)"]
        consumer["PaymentProcessingConsumer<br/>StatementProjectionConsumer<br/>WebhookDeliveryConsumer (Kafka)"]
        scheduler["ReconciliationScheduler<br/>WebhookDispatchScheduler"]
    end

    subgraph app["Application"]
        uc["Primary ports<br/>CreatePaymentUseCase · GetPaymentUseCase<br/>CancelPaymentUseCase · RequestPaymentExecutionUseCase<br/>ExecutePaymentUseCase · RefundPaymentUseCase · GetRefundsUseCase<br/>ReconcilePaymentsUseCase · ReconcileRefundsUseCase<br/>GetStatementUseCase · ProjectPaymentEventUseCase<br/>RegisterWebhookUseCase · GetWebhooksUseCase · DeleteWebhookUseCase<br/>DeliverWebhookUseCase · DispatchWebhooksUseCase"]
        services["Application services"]
        domain["Domain<br/>Payment · Refund · WebhookSubscription · value objects"]
        sp["Secondary ports"]
        uc --> services --> domain
        services --> sp
    end

    subgraph secondary["Secondary adapters"]
        jpa["JPA → PostgreSQL<br/>PaymentRepository · RefundRepository<br/>StatementRepository · IdempotencyPort · AuditPort"]
        outbox["Outbox table<br/>PaymentEventPort"]
        relay["PaymentEventRelay → Kafka<br/>payment-events (Avro)"]
        publisher["Kafka publisher<br/>PaymentProcessingPort"]
        feign["Feign → external systems<br/>AccountEnquiryPort · PaymentAuthorizationPort<br/>PaymentLimitPort · PaymentExecutionPort"]
        metrics["Micrometer<br/>PaymentMetricsPort"]
        webhooks["SQL → PostgreSQL<br/>WebhookRepository"]
        sender["HTTPS → customers' endpoints<br/>WebhookSender"]
    end

    web --> uc
    consumer --> uc
    scheduler --> uc
    sp --> jpa
    sp --> outbox
    outbox -. polled by .-> relay
    sp --> publisher
    sp --> feign
    sp --> metrics
    sp --> webhooks
    sp --> sender
```

Dependencies point inwards. The domain imports no Spring, JPA or Feign, and ArchUnit
fails the build if it does (14, 20).

**Tech stack:** Java 25, Spring Boot 4.1, PostgreSQL, Flyway, Kafka, Avro, Apicurio Registry, OpenFeign, Apache HttpClient,
Resilience4j, Spring Security (OAuth2 resource server), Bucket4j, ShedLock, springdoc-openapi,
Micrometer, OpenTelemetry, Prometheus, Alertmanager, Grafana, Tempo, Loki, Testcontainers, WireMock,
ArchUnit, Kubernetes (Kustomize, kind).

## Quick start

Requirements: Java 25 and Docker. Maven is provided by the wrapper. The full stack (the
service plus PostgreSQL, Kafka, a schema registry, WireMock, Prometheus, Alertmanager, Grafana, Tempo and
Loki) uses about 1.5 GB of memory.

```bash
./mvnw verify                             # build and run all tests
./mvnw spring-boot:run                    # start on :8080; the containers in docker-compose.yml start with it
docker compose --profile app up --build   # or run the service in a container too, as in production
deploy/k8s/smoke.sh                       # or on a local Kubernetes cluster (kind), tested through a rolling restart
```

The Kubernetes path also needs kind, kubectl, jq and Docker's buildx plugin (Docker Desktop has it; on Ubuntu's
`docker.io`, `sudo apt install docker-buildx`).

### Authentication

Since B1, every `/payments` request needs a bearer token. Ready-made local tokens are at
the top of [http/payments.http](http/payments.http): `CUST-1001` holds `ACC-10001` and
`ACC-20001`, and `CUST-2002` holds `ACC-30001`.

They are signed with a key committed in `application-local.properties` and never expire.
Local use only.

### Create a payment, then read it back

```bash
TOKEN=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJDVVNULTEwMDEifQ.dBtBctssK74kMTXqNo4zPSkk--7cSdouBA4q40hCBb8

curl -s -X POST http://localhost:8080/payments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"sourceAccountId":"ACC-10001","destinationAccountId":"ACC-20001","amount":250.00,"currency":"EUR","reference":"Invoice 12345"}'
# 201 {"paymentId":"3dae5be2-…","status":"CREATED",…}

curl -s http://localhost:8080/payments/<paymentId> -H "Authorization: Bearer $TOKEN"
# 200, the same payment
```

Without the `Authorization` header the answer is `401`.

### What's running

| What | URL |
|---|---|
| API | <http://localhost:8080> |
| Swagger UI | <http://localhost:8080/swagger-ui.html> |
| OpenAPI | <http://localhost:8080/v3/api-docs> |
| Grafana (anonymous admin) | <http://localhost:3000> |
| Prometheus | <http://localhost:9090> |
| Alertmanager | <http://localhost:9093> |
| WireMock (fake external systems) | <http://localhost:8089> |

## How to follow along

Each episode is a git tag. Check one out, and diff it against the one before:

```bash
git checkout episode-10-idempotency                                 # the code as episode 10 leaves it
git diff episode-09-payment-execution episode-10-idempotency        # exactly what episode 10 changed
git diff --stat episode-09-payment-execution episode-10-idempotency
```

Then read the episode, read the diff, run that episode's block in
[http/payments.http](http/payments.http), and run the tests it names.
`payments.http` works with the IntelliJ HTTP Client and the VS Code REST Client.

On early tags, `payments.http` and the API have no auth header and no `Idempotency-Key`.
They match their episode.

## Episodes

Four parts: the core of a working service, then correctness when things go wrong
concurrently, then what it takes to operate it, then what a production review asks for.
Each part builds on the one before, in order.

### Part 1 — The core

| # | Episode | Key concept | Tag |
|---|---|---|---|
| 01 | [Create a Working Payment](docs/episodes/01-working-payment.md) | Domain rules in plain Java; self-validating value objects | `episode-01-working-payment` |
| 02 | [Establish the Primary Port Boundary](docs/episodes/02-primary-port-boundary.md) | The controller depends on a primary port, not a class | `episode-02-primary-port` |
| 03 | [Account Enquiry](docs/episodes/03-account-enquiry.md) | First secondary port; the service doesn't own account data | `episode-03-account-enquiry` |
| 04 | [Persist Payments](docs/episodes/04-persist-payments.md) | Persistence behind `PaymentRepository`; the domain is not the JPA entity | `episode-04-persistence` |
| 05 | [Query a Payment](docs/episodes/05-query-payment.md) | First query; commands and queries use different models | `episode-05-payment-query` |
| 06 | [Payment Validation](docs/episodes/06-payment-validation.md) | Four kinds of validation and where each lives | `episode-06-validation` |
| 07 | [External Account System](docs/episodes/07-account-system.md) | Stub replaced by a real Feign adapter, port unchanged | `episode-07-account-system` |
| 08 | [Error Handling](docs/episodes/08-error-handling.md) | Application exceptions; HTTP problem details | `episode-08-error-handling` |
| 09 | [Execute Payment](docs/episodes/09-payment-execution.md) | A lifecycle the aggregate enforces | `episode-09-payment-execution` |

### Part 2 — Correctness

| # | Episode | Key concept | Tag |
|---|---|---|---|
| 10 | [Idempotency](docs/episodes/10-idempotency.md) | `Idempotency-Key`, request fingerprint, database-enforced claim | `episode-10-idempotency` |
| 11 | [Cancel Payment](docs/episodes/11-cancel-payment.md) | Cancel only before money can move | `episode-11-cancellation` |
| 12 | [Authorization and Limits](docs/episodes/12-authorization-limits.md) | External authorization and limits as ports | `episode-12-authorization-limits` |
| 13 | [Transactions and Audit](docs/episodes/13-transactions-audit.md) | Short transactions, never across an external call; audit trail | `episode-13-transactions-audit` |
| 14 | [Architecture Tests](docs/episodes/14-architecture-tests.md) | Architecture rules as ArchUnit tests | `episode-14-architecture-tests` |

### Part 3 — Operability

| # | Episode | Key concept | Tag |
|---|---|---|---|
| 15 | [Observability](docs/episodes/15-observability.md) | Correlation IDs, JSON logs, Prometheus metrics | `episode-15-observability` |
| 16 | [Resilience](docs/episodes/16-resilience.md) | Timeouts, safe retries, circuit breakers; a timeout is not a "no" | `episode-16-resilience` |
| 17 | [Asynchronous Processing](docs/episodes/17-async-processing.md) | Execution through Kafka, retry topics, dead-letter topic | `episode-17-async-processing` |
| 18 | [Testing Strategy](docs/episodes/18-testing-strategy.md) | Test each behaviour at the cheapest level that catches the bug | `episode-18-testing-strategy` |
| 19 | [Production-Style Local Environment](docs/episodes/19-local-environment.md) | A production-style container, configured from outside | `episode-19-local-environment` |
| 20 | [Final Architecture Review](docs/episodes/20-final-review.md) | Architecture review; the dependency rule enforced; OpenAPI | `episode-20-final-review` |

### Part 4 — Production hardening

| # | Episode | Key concept | Tag |
|---|---|---|---|
| B1 | [Security](docs/episodes/bonus-01-security.md) | OAuth2 resource server; customers see only their own payments | `bonus-01-security` |
| B2 | [Reconciliation](docs/episodes/bonus-02-reconciliation.md) | Settling payments whose outcome is unknown | `bonus-02-reconciliation` |
| B3 | [Payment Events](docs/episodes/bonus-03-payment-events.md) | Transactional outbox; at-least-once events | `bonus-03-payment-events` |
| B4 | [Refunds](docs/episodes/bonus-04-refunds.md) | Partial refunds without over-refunding under concurrency | `bonus-04-refunds` |
| B5 | [Distributed Tracing](docs/episodes/bonus-05-distributed-tracing.md) | One trace across HTTP, Kafka, external systems and the outbox | `bonus-05-distributed-tracing` |
| B6 | [Centralized Logs](docs/episodes/bonus-06-centralized-logs.md) | Centralized logs in Loki, linked to traces | `bonus-06-centralized-logs` |
| B7 | [More Than One Instance](docs/episodes/bonus-07-multiple-instances.md) | Several instances: `SKIP LOCKED`, coordinated schedulers, refund reconciliation | `bonus-07-multiple-instances` |
| B8 | [Alerting and SLOs](docs/episodes/bonus-08-alerting-slos.md) | SLOs with burn-rate alerts, one page per incident, from alert to trace | `bonus-08-alerting-slos` |
| B9 | [Payment Statement](docs/episodes/bonus-09-payment-statement.md) | A read model (CQRS) fed by payment events, eventually consistent, rebuilt by replay | `bonus-09-payment-statement` |
| B10 | [Versioned Event Schemas](docs/episodes/bonus-10-event-schemas.md) | Avro with a schema registry; a change that breaks a consumer fails the build | `bonus-10-event-schemas` |
| B11 | [Webhooks](docs/episodes/bonus-11-webhooks.md) | Signed webhooks with durable retries; customer URLs kept out of our network (SSRF) | `bonus-11-webhooks` |
| B12 | [Rate Limits per Customer](docs/episodes/bonus-12-rate-limits.md) | Token buckets per customer, shared by every instance; `429` with `Retry-After`; fail open | `bonus-12-rate-limits` |
| B13 | [Deploying to Kubernetes](docs/episodes/bonus-13-kubernetes.md) | Pods replaced without a failed request: probes, graceful shutdown, expand/contract migrations | `bonus-13-kubernetes` |

## Shortcuts in the code

A comment that starts with `SHORTCUT:` marks a deliberate simplification. It says what was
left out and when to add it:

```java
// SHORTCUT: all supported currencies use 2 minor units; add a field when JPY-style currencies arrive
```

Each episode also ends with a **Known shortcuts** section. (Tags up to and including B7 use the
marker `ponytail:` for the same thing.)

## What this is not

- **Not production-ready.** It is a reference and a teaching tool.
- **The external systems are fakes.** Accounts, authorization, limits and the payment
  system are one WireMock container ([19](docs/episodes/19-local-environment.md)).
- **Credentials are committed on purpose.** The database login and the JWT signing key are
  examples for local use only ([19](docs/episodes/19-local-environment.md),
  [B1](docs/episodes/bonus-01-security.md)).
- **Some shortcuts are still open.** The most important:
  - A delayed first order can still execute after reconciliation has failed its payment
    or refund. The idempotency key stops duplicates, not a late original
    ([B2](docs/episodes/bonus-02-reconciliation.md),
    [B7](docs/episodes/bonus-07-multiple-instances.md)).
  - Nothing reads the dead-letter topic. A parked message is only logged
    ([17](docs/episodes/17-async-processing.md)).
  - Idempotency keys are kept forever ([10](docs/episodes/10-idempotency.md)).
  - Alerts reach a WireMock stand-in, not a pager, and the SLO thresholds are textbook numbers,
    not tuned on real traffic ([B8](docs/episodes/bonus-08-alerting-slos.md)).
  - `payment-events` is kept forever so the statement can be rebuilt; there is no snapshot
    to let it shrink ([B9](docs/episodes/bonus-09-payment-statement.md)).
  - JSON events already on `payment-events` can't be read as Avro; there is no cutover
    for them ([B10](docs/episodes/bonus-10-event-schemas.md)).
  - Webhook secrets are stored unencrypted and can't be rotated
    ([B11](docs/episodes/bonus-11-webhooks.md)).
  - Rate limits are the same for every customer, and nothing limits requests per IP address
    before authentication ([B12](docs/episodes/bonus-12-rate-limits.md)).
  - On Kubernetes: no mTLS or network policies between services, no GitOps, and secrets are
    committed examples rather than from a vault ([B13](docs/episodes/bonus-13-kubernetes.md)).

## About

Built by Dimitrios Sofianos ([CodeAIForge](https://www.codeaiforge.com/)) as a worked
example of how a payment service's architecture follows from its requirements.

Questions and feedback are welcome in
[GitHub Discussions](https://github.com/dsofianos78/java-payment-service/discussions).
Found a bug, or a claim in an episode that isn't true? Open an
[issue](https://github.com/dsofianos78/java-payment-service/issues).

## License

[MIT](LICENSE)
