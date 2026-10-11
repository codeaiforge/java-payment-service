# Bonus Episode 12 — Rate Limits per Customer

Git tag: `bonus-12-rate-limits` · Builds on: [Bonus Episode 11 — Webhooks](bonus-11-webhooks.md)

## Goal

One customer's script must not slow the service down for everyone. Each
customer may create at most **10 payments a minute** and make at most **20
requests a second** overall. Above that the service answers `429 Too Many
Requests` with `Retry-After`, and does no work: no account lookup, no
idempotency claim, no row.

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 6
RateLimit-Policy: "requests";q=20;w=1, "payments";q=10;w=60
RateLimit: "requests";r=17;t=1, "payments";r=0;t=6
Content-Type: application/problem+json

{"type":"about:blank","title":"Too Many Requests","status":429,
 "detail":"Rate limit 'payments' exceeded; retry in 6 seconds"}
```

Payment business rules don't change.

## Two kinds of limits

The service already has a **payment limit** (Episode 12): an external limit
system decides whether this customer may move this much money. That is a
business decision. It needs the payment's amount, it answers `422`, and asking
again a second later gets the same answer.

A **rate limit** is technical protection. It asks only "may this caller make
another request now?", knows nothing about money, and answers `429`. Waiting
is enough to make the same request succeed.

The names stay as they are. `PaymentLimitPort` and
`PaymentLimitExceededException` are still the business limit. Everything new
here is called rate limit, and none of it is in the application.

## Token bucket

Each customer has one bucket per limit. A bucket holds at most 10 tokens (the
payments limit). Every request takes one, and tokens come back steadily, one
every 6 seconds. An empty bucket is a `429`.

The alternative is a **fixed window**: count requests per calendar minute,
reset at :00. It is simpler but has two problems:

- **Bursts at the boundary.** 10 requests at 12:00:59 and 10 more at 12:01:00
  is 20 in two seconds, all allowed.
- **Everyone resets at once.** Every throttled client retries at :00 together.

A token bucket has neither. It also allows what a fixed window does well: a
customer who has been quiet can make 10 requests at once. Over any minute, a
token bucket allows at most its capacity plus what refills in that minute.
"10 a minute" really means "10 at once, then one every 6 seconds".

The refill is **greedy** (Bucket4j's word): the bucket gains a fraction of a
token continuously, rather than all 10 at the top of each minute.
`refillIntervally` would be a fixed window that starts at the customer's
first request.

## Shared across instances

Since B7 the service runs as several instances. A bucket in one instance's
memory lets a customer through 10 times **per instance**. With three
instances behind a round-robin load balancer, that's 30 payments a minute,
and the true limit depends on how many pods happen to be running.

So the buckets live in PostgreSQL, in a new table `rate_limit_bucket` (V12),
through [Bucket4j](https://bucket4j.com/)'s PostgreSQL proxy manager
([RateLimitConfiguration](../../src/main/java/com/example/payment/config/RateLimitConfiguration.java)).
Taking a token is one short transaction:

```sql
SELECT state FROM rate_limit_bucket WHERE id = 'payments:10/PT1M:CUST-1001' FOR UPDATE;
-- Bucket4j works out the refill since the last request, and takes a token, in the JVM
UPDATE rate_limit_bucket SET state = ? WHERE id = 'payments:10/PT1M:CUST-1001';
COMMIT;
```

Two instances taking from the same customer's bucket at the same moment wait
for each other on the row lock. Other customers' rows aren't touched. No new
infrastructure: the database is already there, and so is B7's habit of using
it to coordinate instances (`SKIP LOCKED`, ShedLock).

The **key carries the limit's numbers** (`payments:10/PT1M:CUST-1001`). A
stored bucket remembers its own size. If the key were only
`payments:CUST-1001`, raising the limit to 30 would leave every existing
customer at 10 forever. With the numbers in the key, a changed limit starts
everyone on a fresh bucket of the new size. Bucket4j can also replace the
configuration in place, but only when a version number is increased by hand
at each change. Someone will forget to do that.

### When PostgreSQL is the wrong place

| Store | Wins when |
|---|---|
| **PostgreSQL** (this episode) | Modest traffic, and you'd rather not run anything new. Every limited request costs one more short transaction and a pooled connection. |
| **Redis** | Many requests per second. Taking a token is one atomic script, with no row lock and no transaction. Bucket4j has a Redis proxy manager, so only the configuration class would change. |
| **API gateway** (Kong, Envoy, a cloud gateway) | Limits that belong to the platform: per-IP limits before authentication, quotas sold per plan, limits applied the same way across many services. The request never reaches the service at all. |

The cost of PostgreSQL shows exactly when a customer is abusive. A script
firing 100 requests at once makes 100 transactions queue on the same row.
Each one holds a pooled connection while it waits, and Hikari has 10 by
default. That is worth measuring before the limiter becomes the thing that
slows everyone down. At that point Redis is the answer.

## Where the check runs

[RateLimitFilter](../../src/main/java/com/example/payment/infrastructure/adapter/primary/web/RateLimitFilter.java)
is a servlet filter in the web adapter. It runs after Spring Security's
filters, because it needs the customer ID (the token's `sub`, as in B1), and
before the controllers. Spring Boot registers it at the lowest precedence,
behind the security filter chain.

```text
CorrelationIdFilter → observation (http.server.requests) → Spring Security → RateLimitFilter → controller
```

- **Not in the application.** No use case knows about request rates, just as
  none knows about HTTP. A rate limit protects this deployment's resources.
  If the same use case were driven from Kafka, it would need a different
  protection.
- **After the metrics filter.** A `429` is counted in `http.server.requests`
  like any other answer.
- **Every authenticated request** takes a token from `requests`.
  `POST /payments` takes one from `payments` too. Requests are checked first,
  and a request refused there doesn't spend a payment token.
- **No customer, no limit here.** A request without a valid token is refused
  by Spring Security (`401`) before it gets here. Health, metrics and the API
  documentation have no customer. Per-IP limits belong at the edge (gateway,
  load balancer, WAF), where an attacker without a token is stopped before
  costing anything. This service has none (Known shortcuts).

### Idempotent retries count

A retry with the same `Idempotency-Key` takes a token like any other request.
The limit is there to protect the service, and a retry still costs it a
request: authentication, the idempotency lookup, a response. A script stuck
retrying one key in a loop is exactly the case the limit exists for.
Exempting retries would also mean reading the idempotency table in the filter,
before the limit, which is the expensive work the limit is meant to skip. A
well-behaved client retries with backoff and stays far below 10 a minute.

## The response

- **`429` as a problem detail**, like every other error since Episode 8. The
  filter writes it itself: it runs before Spring MVC, so
  `PaymentExceptionHandler` never sees it.
- **`Retry-After`** in whole seconds, rounded up: how long until the next
  token. A client that waits that long will find one.
- **`RateLimit-Policy` and `RateLimit`**, on every answer that went through
  the limiter, `200`s included. These are from the IETF HTTPAPI working
  group's draft, *RateLimit header fields for HTTP*
  (`draft-ietf-httpapi-ratelimit-headers`, still a draft):
  - `RateLimit-Policy: "payments";q=10;w=60`: the policy's name, its quota
    `q`, and its window `w` in seconds.
  - `RateLimit: "payments";r=3;t=42`: what is left, `r`, and `t`, the seconds
    within which no more than `r` can be used. Here `t` is when the bucket
    is full again, or, when it's empty, when the next token arrives.

  Earlier drafts, and many APIs (GitHub's `X-RateLimit-*`), use three
  separate headers instead: `RateLimit-Limit`, `RateLimit-Remaining` and
  `RateLimit-Reset`. The draft replaced them with these two structured
  fields, which can describe several policies at once. A client that ignores
  them still has `Retry-After`, which is a standard.

## When the store is down

If PostgreSQL doesn't answer, the limiter can **fail closed** (refuse every
request) or **fail open** (let every request through, unlimited).

It **fails open**. The limiter protects the service from heavy use, and a
limiter outage is not heavy use. Failing closed would turn "the limiter's
store is down" into "the service is down", for every customer, including
reads that might still work. The price is that nobody is limited while it
lasts.

That is acceptable here because the store *is* the service's own database.
If it's down, payments can't be created anyway. Each failure is counted
(`rate.limit.unavailable`) and logged as a warning, without the customer ID.
With a separate store (Redis), fail open is still the usual choice, but it
needs an alert: an outage there would silently remove every limit while the
service looked healthy.

## Observability

- `rate_limit_rejected_total{limit="requests"|"payments"}`: requests refused,
  by limit. **No customer label.** Every customer would be a new time series
  (unbounded cardinality), and a customer ID in metrics is personal data
  (Episode 15, B6). To find who is being limited, use the `429`s in the
  traces and access logs, not the metric.
- `rate_limit_unavailable_total`: requests let through because the store
  didn't answer.

Both are registered at 0 at start-up, so Prometheus sees the first increase
(as B8 did for its SLO counters).

## Three protections, three directions

| | Protects | From | Answer |
|---|---|---|---|
| **Rate limit** (this episode) | this service | a caller sending too much | `429`, try later |
| **Payment limit** (Episode 12) | the bank and the customer | moving too much money | `422`, not allowed |
| **Circuit breaker** (Episode 16) | this service | a dependency that stopped answering | `503`, fail fast |

The rate limit faces inwards (callers → us), the circuit breaker faces
outwards (us → the systems we call). The payment limit isn't about load at
all.

## Architecture

```text
config/RateLimitConfiguration                             new: ProxyManager over rate_limit_bucket
infrastructure/adapter/primary/web/RateLimitFilter        new: token buckets per customer; 429; fail open
```

Nothing in `domain` or `application` changes. This is a primary adapter
concern: how this HTTP endpoint protects itself.

## What changed

```text
pom.xml                                                          + bucket4j_jdk17-postgresql 8.21.0
src/main/resources/application.properties                       + payment.rate-limit.*
src/main/resources/db/migration/V12__create_rate_limit_bucket.sql   new: rate_limit_bucket
http/payments.http                                               + Bonus 12 requests
src/main/java/com/example/payment/
├── config/RateLimitConfiguration.java                          new
└── infrastructure/adapter/primary/web/RateLimitFilter.java     new
```

## Known shortcuts

- **No per-IP limit at the edge.** A flood of requests without a valid token
  is refused by Spring Security, but each one still costs parsing and
  checking the token's signature. A per-IP limit belongs in a gateway or load
  balancer in front of the service.
- **The same limits for every customer.** No tiers: a merchant with a
  thousand payments an hour has the same limit as a person paying rent. A
  tier would come from the token (a claim) and choose the bucket size, with
  the tier in the key.
- **Bucket rows are never deleted.** One row per customer and limit, plus the
  old ones after a limit changes. A bucket untouched for a minute is full,
  the same as having no row. Delete rows older than a day on a schedule, as
  the outbox does (B7), once the table grows enough to matter.
- **Each request is one more transaction.** Two for `POST /payments`. See
  "When PostgreSQL is the wrong place".
- **The instances' clocks are trusted.** Bucket4j computes the refill from
  the calling instance's clock, not the database's. A few milliseconds of
  drift between instances shift a token by a few milliseconds. NTP keeps it
  there.

## Try it

```bash
docker compose up -d
./mvnw spring-boot:run
```

1. **Headers on every answer.** Bonus 12's first request in
   `http/payments.http`. The response has `RateLimit-Policy` and `RateLimit`.
2. **Hit the limit.** Run Bonus 12's second request 11 times within a minute.
   It's one retried key, so the first 10 answer `201` with the same payment,
   and the 11th is `429` with `Retry-After`. Run the same request as
   CUST-2002 (`{{otherToken}}`, from `ACC-30001`), and it goes through.
3. **The metric.** `curl -s localhost:8080/actuator/prometheus | grep rate_limit`
   shows `rate_limit_rejected_total{limit="payments"} 1.0`.
4. **The store is shared.** `SELECT id FROM rate_limit_bucket;` shows one row
   per customer and limit. Start a second instance
   (`./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081`)
   and alternate between the two ports: together they allow 10, not 20.

## Tests

| Test | Change |
|---|---|
| `RateLimitFilterTest` | **new, 7.** Buckets in a map instead of PostgreSQL (Bucket4j's compare-and-swap contract), and a clock the test moves. Under the limit passes, with `RateLimit-Policy` and `RateLimit`. The 3rd payment at 2 a minute is `429` with `Retry-After: 30`, a problem detail, the rejection counted, and the controller not reached. A token comes back after 30 seconds. Every request counts towards the request limit. Another customer is unaffected. An unauthenticated request is not limited and creates no bucket. With the store down, requests pass with no headers and each failure is counted. |
| `MultipleInstancesTest` | **+1.** Two instances over real HTTP, alternating, with a signed token: 10 payments are created, and the 11th and 12th are `429`. Buckets in each instance's memory would have let all 12 through. |
| `PaymentEndToEndTest` | **+2.** The 11th payment in a minute is `429` with `Retry-After`, the headers and a problem detail. It creates no payment and doesn't call the account system. The rejection shows in Prometheus, and another customer can still create one. 10 retries of one idempotency key give one payment, and the 11th is `429`. Every test now starts with full buckets. |

358 tests, passing.
