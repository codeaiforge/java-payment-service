# Bonus Episode 11 — Webhooks

Git tag: `bonus-11-webhooks` · Builds on: [Bonus Episode 10 — Versioned Event Schemas](bonus-10-event-schemas.md)

## Goal

A customer's own system, in the story a merchant's shop, must hear when one
of their payments reaches a final state, without polling `GET /payments/{id}`.
The customer registers an HTTPS endpoint. The service POSTs each of their
payment events to it, signed, and retries until it is delivered or given up.

```http
POST /hooks HTTP/1.1
Host: shop.example.com
Content-Type: application/json
Webhook-Id: 3f0d7c3e-5b1a-4c51-9a55-0b7f2e6d1c42
Webhook-Timestamp: 1760176800
Webhook-Signature: v1=5c1f…e9

{"id":"3f0d7c3e-…","type":"payment.completed","occurredAt":"2026-10-11T10:00:00.123456Z",
 "data":{"paymentId":"07c87da8-…","refundId":null,"sourceAccountId":"ACC-10001",
         "destinationAccountId":"ACC-20001","amount":"250.00","currency":"EUR","reference":"Invoice 12345"}}
```

Payment and refund rules don't change.

## The subscription

`WebhookSubscription` is a new domain entity: an ID, the customer, the URL,
the secret, a status (`ACTIVE`, `DISABLED`) and a count of consecutive failed
deliveries. It holds two rules:

- **The URL must be `https`.** Plain HTTP would carry the payment and its
  signature readable to anyone on the way. A URL with a user and password in
  it is refused too, since it would be stored and shown back on every list.
- **An endpoint that keeps failing is switched off.** After five deliveries in
  a row are given up, it becomes `DISABLED` and gets nothing more. A delivery
  that arrives resets the count. It never comes back on its own: the customer
  registers it again. A delivery is only given up after a day of retries
  (below), so five in a row means the endpoint has been down for a day, or
  rejects everything we send.

Whether the URL's host is one we may call at all (not inside our own network)
is not a domain rule, because only DNS can answer it. That check is the
sender's (see SSRF).

### Endpoints

| Request | Answer |
|---|---|
| `POST /webhooks {"url": "https://…"}` | `201` with `webhookId`, `url`, `status` and `secret` |
| `GET /webhooks` | `200`, the caller's endpoints, without secrets |
| `DELETE /webhooks/{id}` | `204`, and every delivery still waiting for it is deleted too |

The customer comes from the token (B1), as everywhere. Another customer's
webhook is a `404`, as another customer's payment is, so the API never
confirms that an ID exists. **The secret is shown once**, in the answer to the
registration. The customer stores it then, and no later request can read it
back. Someone who later gets read access to the API (a leaked token, a support
screen) can't learn it.

Three use cases, as for payments: `RegisterWebhookUseCase`,
`GetWebhooksUseCase` and `DeleteWebhookUseCase`. The secret is 256 random
bits from `SecureRandom`, prefixed `whsec_` so it is recognisable in a
customer's config, or by a secret scanner if it is ever committed.

## From event to delivery

```text
payment-events --WebhookDeliveryConsumer (group payment-webhooks)--> DeliverWebhookUseCase
   --> one webhook_delivery row per active endpoint of the payer
WebhookDispatchScheduler (every 1s, every instance) --> DispatchWebhooksUseCase
   --> claim due rows --> WebhookSender (HTTPS) --> delivered / retry later / given up
```

**The consumer** is a primary adapter, like B9's: it maps the Avro message to
the domain's `PaymentEvent` (B10's schema, the same mapping) and calls
`DeliverWebhookUseCase`. It has its own consumer group, so webhooks and the
statement each read every event at their own pace.

`DeliverWebhookService` asks the account system who holds the payment's
source account: the payer. Each of that customer's active endpoints gets one
`webhook_delivery` row, and nobody else's endpoint hears of the payment. The
payee doesn't either: the event is about the payer's payment, and so are the
payer's webhooks.

**Deliveries are durable work, not retries in memory.** A delivery is a row
from the moment the event arrives until it is delivered or given up. Sending
from the consumer would be simpler, and wrong in two ways:

- An endpoint that is down for an hour would hold up the topic for every
  other customer, or the retries would have to live in memory.
- Retries kept in memory die with the instance. A restart would silently drop
  every delivery that was waiting for its next attempt.

The row holds the event, not the HTTP body, as the outbox does (B3): the
sender owns the public contract. `UNIQUE (subscription_id, event_id)` with
`ON CONFLICT DO NOTHING` makes the consumer idempotent. Kafka delivers at
least once, and a redelivered event is queued only once per endpoint.

### The dispatcher

Every instance runs `WebhookDispatchScheduler`, with no lock, so more
instances send more at once. Each run claims up to 20 due deliveries:

```sql
UPDATE webhook_delivery SET next_attempt_at = :leaseUntil
WHERE id IN (SELECT id FROM webhook_delivery
             WHERE status = 'PENDING' AND next_attempt_at <= :now
             ORDER BY next_attempt_at LIMIT :max
             FOR UPDATE SKIP LOCKED)
RETURNING *
```

`SKIP LOCKED` is B7's. The difference is what happens next. The outbox relay
holds its claim in a transaction while it sends, which is fine for a broker
that answers in milliseconds. A customer's endpoint can take seconds, and a
transaction open that long holds a database connection for nothing. So the
claim is a **lease**: the one statement moves `next_attempt_at` five minutes
ahead, which is longer than 20 sends can take within the sender's deadlines.
Until then no other instance's claim sees the row. If the instance dies
mid-send, the lease runs out and another instance claims the delivery. The
endpoint may then get it twice, which receivers must expect anyway
(`Webhook-Id`, below).

Each answer is recorded in its own short transaction:

| Answer | Then |
|---|---|
| `2xx` | `DELIVERED`. The subscription's failure count goes back to 0. |
| anything else (an error status, a redirect, no answer, a refused address) | tried again after a wait |
| still failing 24 hours after it was queued | `FAILED`, and one more failure on the subscription's count, which may disable it |

The wait doubles from 30 seconds (30s, 1m, 2m … 32m) up to an hour, and each
wait is shortened by a random amount up to half its length (jitter). Without
jitter, every endpoint that failed during one outage of the customer's
hosting provider would come back at exactly the same moments and hit it
together. A day covers an overnight outage, about 30 to 50 attempts.

The subscription row is locked (`SELECT … FOR UPDATE`) while its count
changes. Two instances can finish deliveries to the same endpoint at the same
moment, and each one must count.

`Clock` is a bean now (`config/BeanConfiguration`). The webhook services read
the time from it, so a test can move a day forward in milliseconds.

## What the receiver gets

### Headers

| Header | Value | For the receiver |
|---|---|---|
| `Webhook-Id` | the event ID, the same on every retry | deduplication |
| `Webhook-Timestamp` | Unix seconds when this attempt was sent | replay protection |
| `Webhook-Signature` | `v1=` + hex HMAC-SHA256 over `id.timestamp.body`, keyed with the secret | authenticity |

The receiver recomputes the signature over the bytes it received, with its
copy of the secret, and compares. Two details are on the receiver's side, and
the public documentation of this API must spell them out:

- **Reject old timestamps.** An attacker who records one request (from a
  proxy log, say) could send it again a week later, and the signature would
  still be valid. The timestamp is signed, so it can't be changed, and a
  receiver that refuses anything more than about five minutes old makes a
  recorded request useless.
- **Compare in constant time** (`MessageDigest.isEqual` in Java,
  `hmac.compare_digest` in Python). `String.equals` stops at the first byte
  that differs, so the time it takes reveals how many leading bytes an
  attacker guessed right, and a signature can be found byte by byte.

The ID is signed too, so it can't be swapped to get past the receiver's
deduplication. The `v1=` prefix leaves room for another scheme later, or for
two signatures side by side while a secret is rotated (see Known shortcuts).

`WebhookSignatureTest` pins a fixed vector: a known secret, ID, timestamp and
body, and the signature they give, so a receiver in any language can check
their code against ours.

### A public contract, not the Avro record

The body is `WebhookPayload`, written for customers, and not the Avro record
from `payment-events`. They look similar, but they are different contracts
that change for different reasons:

- **Different readers.** `payment-events`' consumers are our own teams. They
  read our registry, follow its compatibility rules and redeploy when asked.
  A merchant's code changes when the merchant chooses, maybe never, and they
  can't read our registry.
- **Different freedom.** Internally, B10's rules allow evolving the schema
  under `FULL_TRANSITIVE`. Publicly, the contract only grows: a field is never
  renamed or removed.
- **Different words.** Event types are `payment.completed`, not
  `PAYMENT_COMPLETED`, and the mapping is an explicit `switch` with no
  default. A new internal event type doesn't compile until someone decides
  whether customers get it and what it is called. Without that switch, a new
  internal event would be published to customers as a side effect.
- **Every value is a string.** Amounts are written exactly (`"250.00"`), so no
  client parses money into a binary floating-point number. Times are ISO-8601
  in UTC.

### No `traceparent`

Every other outbound call carries the W3C `traceparent` header (B5). Webhooks
don't, and the sender has no tracing at all:

- Our trace IDs are internal. A customer who sees them learns how we trace,
  and could put them in a support ticket ("trace 4bf9…"), which mixes the two
  sides' data.
- A customer who sends them back, in a later call to our API, could join our
  traces or pick IDs that collide with ours.
- A client span would also carry the URL, which must never reach Tempo
  (Security, below).

`WebhookDeliveryTest` and the end-to-end test both check that no
`traceparent` arrives at the merchant.

## Security: the endpoint is untrusted

Every other system this service calls is ours, or a partner's, configured by
us. A webhook URL is typed in by a customer, so the service treats it as
hostile.

### SSRF

A customer could register `https://169.254.169.254/latest/meta-data/`, the
cloud metadata service, which hands out the instance's credentials to anyone
who asks from inside. Or `https://10.0.0.5/admin`, or a hostname of their own
whose DNS points at either. The service would then call it from inside our
network, with the network's trust, and whatever came back could leak through
the delivery's status. This is server-side request forgery. Each step below
closes one way in.

1. **Resolve and check at registration.** `RegisterWebhookService` asks the
   sender whether the host resolves only to public addresses. If not, the
   answer is `400`, and nothing is stored. A host that doesn't resolve at all
   is refused too.
2. **Refuse every address that isn't public**, not only the obvious ones
   (`PublicAddressResolver.isPublic`): loopback, private (`10/8`,
   `172.16/12`, `192.168/16`), link-local (`169.254/16`, where the metadata
   service lives, and `fe80::/10`), carrier-grade NAT (`100.64/10`), IPv6
   unique local (`fc00::/7`, where AWS's IPv6 metadata service lives),
   multicast, reserved and broadcast, and the IPv6 forms that carry an IPv4
   address inside them (`::ffff:a.b.c.d`, `::a.b.c.d`, NAT64, 6to4). A host
   with several addresses is refused if any one of them isn't public.
3. **Resolve and check again on every send.** DNS can answer differently
   later. A host that was public at registration can point inward a month
   after, or a second after: a DNS server can give a public address with a
   TTL of 0, then `127.0.0.1` on the next lookup. That is **DNS rebinding**.
4. **Pin the checked address.** A check followed by the HTTP call would look
   the name up twice, and the rebinding attack lives in the gap between the
   two lookups. So the check isn't a separate step: `PublicAddressResolver` is
   the HTTP client's `DnsResolver`. The client makes no lookup of its own and
   connects only to the addresses that resolver returned. TLS still checks the
   certificate against the hostname, not the IP.
5. **No redirects followed.** A `302` to `http://169.254.169.254/` would get
   around every check above, since nobody checked where it points. A redirect
   is a failed delivery.

This is why the sender uses Apache HttpClient 5 (new dependency, version from
Spring Boot) rather than the JDK's `HttpClient`, which has no way to plug in
the resolver. And not Feign: a Feign client is one configured system, and here
every customer brings their own URL.

In production this is the inner fence. The outer one is the network: webhooks
leave through an egress proxy or a subnet whose firewall can't reach the
metadata service or internal ranges at all, so that a bug in this check is
not an open door.

### Untrusted answers

- **Short deadlines.** 3 seconds to connect, 10 to answer, and 15 for the
  whole exchange, after which the request is cancelled. The per-read deadline
  alone wouldn't stop an endpoint that sends its headers a byte every nine
  seconds. One slow endpoint holds up the other deliveries in its batch by
  seconds, not minutes.
- **The response body is never read.** Only the status counts, so a huge or
  hostile body costs nothing and is never logged. The connection is closed
  rather than reused, which costs a TLS handshake per delivery.
- **No automatic retries and no cookies.** Retries are the dispatcher's, with
  backoff and a record in the database.

### Secrets, URLs and customers stay out of logs and spans

Episode 15's rule (no account IDs, customer IDs or references in logs and
metrics) extends here:

- **The secret** signs as us. It is never returned after registration, and
  `WebhookSubscription.toString()` shows only the ID, so the secret can't reach
  a log line by accident.
- **The URL** can hold the customer's own token in its path
  (`/hooks/8f3a…`), and its host can identify the customer. Failures are logged
  by webhook and delivery ID. An exception is logged by class only, since the
  HTTP client's messages name the host.
- **The customer ID**, as before.

The end-to-end test registers two endpoints with a token in their paths, one
answering `200` and one `500`, completes a payment, and checks the console,
every span and every exported log record for both secrets, the paths and the
customer ID.

The secret is stored as it is in the database. See Known shortcuts.

## Testing it locally

WireMock plays the merchant: over HTTPS, with a self-signed certificate for
`127.0.0.1` kept in `src/test/resources/webhooks`, and trusted through a
Spring SSL bundle (`payment.webhooks.ssl-bundle=merchant`). But WireMock
listens on `127.0.0.1`, which the sender refuses. The tests exempt exactly that
address with `payment.webhooks.allowed-private-addresses=127.0.0.1`. It is
empty by default, and setting it anywhere else opens the network the check
exists to protect.

For the same reason, locally a webhook can't reach anything on your own
machine. Use a public request inspector such as webhook.site (Try it, below).

## Architecture

```text
domain/entity/WebhookSubscription                         new: https only; disabled after 5 failed deliveries
domain/valueobject/WebhookId, WebhookStatus               new
application/port/primary/
   RegisterWebhookUseCase, GetWebhooksUseCase, DeleteWebhookUseCase
   DeliverWebhookUseCase, DispatchWebhooksUseCase         new
application/port/secondary/
   WebhookRepository                                      new: subscriptions and the delivery queue
   WebhookSender                                          new: isPublic(url), send(...)
application/service/command/
   RegisterWebhookService, DeleteWebhookService           new
   DeliverWebhookService                                  new: event -> one delivery per active endpoint of the payer
   DispatchWebhooksService                                new: lease, send, backoff with jitter, give up after 24h
application/service/query/GetWebhooksService              new
config/BeanConfiguration                                  new: Clock
infrastructure/adapter/primary/
   web/WebhookController                                  new: POST/GET/DELETE /webhooks
   messaging/WebhookDeliveryConsumer                      new: group payment-webhooks
   scheduling/WebhookDispatchScheduler                    new
infrastructure/adapter/secondary/
   persistence/WebhookPersistenceAdapter                  new: plain SQL (JdbcClient)
   webhook/HttpWebhookSender                              new: Apache HttpClient 5
   webhook/PublicAddressResolver                          new: the client's DNS resolver; refuses non-public addresses
   webhook/WebhookSignature, WebhookPayload               new: the public contract
```

Two things are new in shape:

- **The persistence adapter is plain SQL**, not JPA. The queue is made of
  statements JPA has no words for (`ON CONFLICT DO NOTHING`, an `UPDATE … FOR
  UPDATE SKIP LOCKED … RETURNING`), and the subscriptions are too small to need
  an entity. It is still an adapter behind a port, so the application doesn't
  know.
- **`WebhookSender` has two methods.** `isPublic(url)` lets registration ask
  the same question the sender asks on every send, from the same code, without
  the application knowing what DNS is.

## What changed

```text
pom.xml                                                          + httpclient5 (version from Spring Boot)
src/main/resources/application.properties                       + payment.webhooks.*
src/main/resources/db/migration/V11__create_webhooks.sql        new: webhook_subscription, webhook_delivery
http/payments.http                                               + Bonus 11 requests
src/main/java/com/example/payment/
├── domain/entity/WebhookSubscription.java                       new
├── domain/valueobject/WebhookId.java, WebhookStatus.java        new
├── application/
│   ├── exception/WebhookNotFoundException.java,
│   │   WebhookValidationException.java                          new
│   ├── port/primary/*Webhook*UseCase.java                       new, 5
│   ├── port/secondary/WebhookRepository.java,
│   │   WebhookSender.java                                       new
│   ├── service/command/RegisterWebhookService.java,
│   │   DeleteWebhookService.java, DeliverWebhookService.java,
│   │   DispatchWebhooksService.java                             new
│   ├── service/query/GetWebhooksService.java                    new
│   └── usecase/command/RegisterWebhookCommand.java,
│       DeleteWebhookCommand.java, usecase/query/GetWebhooksQuery.java   new
├── config/BeanConfiguration.java                                new: Clock
└── infrastructure/adapter/
    ├── primary/web/WebhookController.java, WebhookRequest.java,
    │   WebhookResponse.java                                     new
    ├── primary/web/PaymentExceptionHandler.java                 + webhook exceptions: 400, 404
    ├── primary/messaging/WebhookDeliveryConsumer.java           new
    ├── primary/scheduling/WebhookDispatchScheduler.java         new
    ├── secondary/persistence/WebhookPersistenceAdapter.java     new
    └── secondary/webhook/HttpWebhookSender.java,
        PublicAddressResolver.java, WebhookSignature.java,
        WebhookPayload.java                                      new
src/test/resources/webhooks/merchant.p12, merchant.crt           new: the test merchant's certificate
```

## Discuss

- **At least once, again.** A delivery can arrive twice. Kafka can redeliver
  the event: that one is deduplicated in our table. But the endpoint can
  also answer `200` after our 10-second deadline, or our instance can die
  between sending and recording, and then we send again. No sender can rule
  that out, because it can't tell "they didn't get it" from "their answer
  didn't reach us" (Episode 16's unknown outcome, from the other side). So
  receivers must deduplicate by `Webhook-Id`, the event ID, which is the same
  on every attempt. This goes in the public contract.
- **No ordering.** The events of one payment are in order on the topic (B3),
  but not at the endpoint. `payment.completed` can fail and be retried 30
  seconds later, after the `refund.completed` that came behind it was already
  delivered. Keeping order would mean holding every later event back behind
  one failing delivery. The contract says so instead: there is no order, and
  `occurredAt` tells which came first. A receiver that needs the current state
  can always ask `GET /payments/{id}`.
- **Webhook vs polling vs a customer-facing event stream.** A webhook is
  "we call you". It's cheap for the customer, but we carry the retries, and the
  customer needs an HTTPS endpoint open to the internet. Polling puts the
  load on us without the endpoint. Giving merchants a Kafka topic would be a
  much heavier contract. Most payment APIs offer webhooks plus a read API to
  catch up after an outage.

## Known shortcuts

- **No secret rotation.** Rotating means two active secrets for a while: the
  sender signs with both (`v1=<old>,v1=<new>`), the receiver accepts either,
  and the old one is dropped once the receiver has switched. Until then, a
  leaked secret means deleting the endpoint and registering it again.
- **No delivery log for customers.** The rows have the status, attempts and
  last HTTP status, but no API shows them. A customer whose endpoint was
  disabled can't see why, and can't ask for a failed delivery to be re-sent.
- **No per-endpoint concurrency limit.** Twenty deliveries for one endpoint
  can be sent at once by different instances. A merchant receiving a burst
  gets all of it in parallel.
- **The secret is stored unencrypted.** Anyone who can read
  `webhook_subscription` can sign as us. It belongs encrypted with a key from
  a key management service (envelope encryption), decrypted only to sign. It
  can't be hashed like a password: the sender needs the secret itself.
- **Delivered and failed rows are kept forever.** They belong deleted after a
  retention period, as the outbox's are (B7).
- **A connection per delivery.** The body is never read, so the connection
  can't be reused, and each delivery pays a TLS handshake. Fine at this volume.
  At a higher volume, read and discard up to a small limit instead, and keep
  the connection.
- **Any port.** `https://shop.example.com:8443/` is allowed. Some services allow
  only 443, which narrows what a customer can make us call on a public host.
- **The account system is asked for every event.** The payer is looked up per
  event. If the account system is down, webhooks wait in the retry topics, as
  any failed event does.

## Try it

```bash
docker compose up -d
./mvnw spring-boot:run
```

1. **An endpoint to receive webhooks.** Open https://webhook.site and copy
   your unique URL. It is a public HTTPS endpoint that shows what it receives.
2. **Register it.** Bonus 11's first request in `http/payments.http`, with
   your URL. The answer has `"secret": "whsec_…"`, shown this once.
3. **A payment.** Create and execute a payment from `ACC-10001` (Episode 09's
   requests). Within a second webhook.site shows the POST, with `Webhook-Id`,
   `Webhook-Timestamp` and `Webhook-Signature`.
4. **Check the signature** the way a receiver would, with the body exactly as
   received (webhook.site's "Raw content"):
   ```bash
   printf '%s.%s.%s' "$ID" "$TIMESTAMP" "$BODY" \
     | openssl dgst -sha256 -hmac "$SECRET" | sed 's/^.* /v1=/'
   ```
   The output matches `Webhook-Signature`.
5. **Our own network is refused.** Bonus 11's third request registers
   `https://169.254.169.254/latest/meta-data/` and gets a `400`.
6. **Retries.** Set webhook.site to answer `500` (its "Edit" button), and
   execute another payment. The log shows
   `Webhook delivery … attempt 1 failed (status 500), next attempt at …`, then
   attempts further and further apart. Set it back to `200`, and the next
   attempt is delivered.

## Tests

| Test | Change |
|---|---|
| `WebhookSubscriptionTest` | **new, 6.** https only; no user or password in the URL; disabled after five failed deliveries in a row; a delivery resets the count; `toString` hides the secret, the URL and the customer. |
| `WebhookSignatureTest` | **new, 1.** A fixed vector: a known secret, ID, timestamp and body give a known signature. |
| `PublicAddressResolverTest` | **new, 5.** Registering `https://127.0.0.1/`, `https://10.0.0.1/`, `https://169.254.169.254/`, a hostname that resolves to a private address, and one that resolves to a public and a private address is refused, and nothing is stored. A public one is registered. 26 non-public addresses are refused and 6 public ones resolve. An allowed private address is let through, and no other. |
| `WebhookDeliveryTest` | **new, 7.** WireMock as the merchant, over HTTPS, with the dispatcher, the real sender and a clock the test moves. The signed POST arrives with its headers and body, with no `traceparent`. `500` then `200` is delivered on the retry, not before the wait. An endpoint that always fails is given up after a day, and five such deliveries disable the subscription, which then gets nothing. A redirect is not followed. A host that resolved publicly at registration and now resolves to `127.0.0.1` (DNS rebinding) is not called, and neither is an address written in the URL. |
| `DeliverWebhookServiceTest` | **new, 4.** An event is queued for each active endpoint of the payer, not for a disabled one, another customer's or the payee's. An unknown account goes nowhere. The same event twice is queued once. |
| `WebhookPersistenceAdapterTest` | **new, 5.** Real PostgreSQL. A subscription comes back as saved. An event is queued once per subscription and comes back whole. A claimed delivery is invisible until its lease ends. A retry is due at its time, and a delivered one never again. Deleting a subscription deletes its deliveries. |
| `PaymentEndToEndTest` | **+5.** Registration shows the secret once, and the list never does. http, `10.0.0.1`, `169.254.169.254`, `127.0.0.2` and a non-URL are `400`. A completed payment reaches the payer's endpoint, signed with the secret the registration returned, carrying the event ID, with no `traceparent`. A customer can't see or delete another customer's webhook, and another customer's endpoint isn't queued for the payment. No secret, URL path or customer ID in the console, any span or any exported log record. |

348 tests, passing.
