# Bonus Episode 08 — Alerting and SLOs

Git tag: `bonus-08-alerting-slos` · Builds on: [Bonus Episode 07 — More Than One Instance](bonus-07-multiple-instances.md) · Next: [Bonus Episode 09 — Payment Statement](bonus-09-payment-statement.md)

## Goal

Metrics (Episode 15), traces (B5) and logs (B6) let someone investigate, but
only if someone is already looking. Operations must be told when customers
are affected, not when a CPU is busy, and must be able to go from the alert
to an example trace.

Earlier episodes listed the gaps as known shortcuts:

| Listed in | Gap |
|---|---|
| B5 | No link from metrics to traces: exemplars are in the scrape, nothing stores them. |
| B6 | No log-based alerting. |
| README | No alerting at all: nothing pages anyone. |

There are no business logic changes. The application gains one metric, which
only it can produce, and everything else is configuration.

## SLIs, SLOs and the error budget

- An **SLI** (service level indicator) is a measurement of what customers
  experience, as a ratio: good events over all events.
- An **SLO** (objective) is the target for that ratio over a period: "99.5%
  over 30 days".
- The **error budget** is what the SLO allows to go wrong: 0.5% of the
  month's executions. Spending it isn't a failure. Spending it faster than
  the month allows is what alerts are for.

This service has two SLOs, over 30 days.

| SLO | Good event | Target | Budget |
|---|---|---|---|
| **Success** | A payment execution that ends with an answer: `COMPLETED`, or a business "no" | 99.5% | 0.5% |
| **Latency** | `POST /payments` answered within 500ms | 99% | 1% |

### Why a business "no" is a good event

An execution refused because the account isn't authorized, the payment is
over its limit, or the payment system rejected it (insufficient funds) ended
correctly. The customer got a clear answer and can act on it. If refusals
counted as errors, a wave of customers over their limit would page someone
who can do nothing about it, and the SLO would measure customers' bank
balances rather than the service.

What counts against it is an execution without an answer: an external system
down, an answer lost so the payment is left `PROCESSING`, or a failure of our
own. That's where the customer is left not knowing.

### The metric: only the application can tell them apart

The existing metrics can't express this. `payments_total{status="failed"}`
mixes a rejection with a payment reconciliation failed after its order was
lost. A refused authorization doesn't change the status at all: it's an
exception. Only `ExecutePaymentService` knows which outcome is which, so it
reports it through the port it already has (Episode 15):

```java
// PaymentMetricsPort
void executionEnded(Execution result);

enum Execution { COMPLETED, REFUSED, ERROR }
```

```java
// ExecutePaymentService
public Payment executePayment(ExecutePaymentCommand command) {
    try {
        Payment payment = execute(command);
        paymentMetricsPort.executionEnded(switch (payment.status()) {
            case COMPLETED -> Execution.COMPLETED;
            case FAILED -> Execution.REFUSED;
            default -> Execution.ERROR; // left PROCESSING
        });
        return payment;
    }
    catch (PaymentAuthorizationException | PaymentLimitExceededException e) { /* REFUSED */ }
    catch (PaymentValidationException | PaymentNotFoundException | InvalidPaymentStateException e) { /* not counted */ }
    catch (RuntimeException e) { /* ERROR */ }
}
```

A malformed ID, an unknown payment or a second execute isn't an execution at
all, so it isn't counted either way. `PaymentMetrics` exports
`payment_executions_total{result="completed|refused|error"}`, and registers
all three series up front. A series that first appears at 1 has no increase
that `rate()` can see, so the first error would be lost.

A payment left `PROCESSING` counts as an error when its execution ends.
Reconciliation settling it later doesn't change that: the customer waited
anyway. Executions run on the Kafka consumer (Episode 17), and a message whose
processing throws is retried up to 4 times. Each failed attempt counts. That
is conservative: an outage that retries hide still spends budget.

### The latency SLI: one bucket, not seventy

`http_server_requests` is a timer, published as a sum and a count. "Answered
within 500ms" needs a histogram bucket at exactly 500ms:

```properties
management.metrics.distribution.slo.http.server.requests=500ms
```

That publishes `http_server_requests_seconds_bucket{le="0.5"}` (and `+Inf`)
for each `uri`, `method`, `status` and `outcome`. The alternative,
`percentiles-histogram=true`, publishes about 70 buckets per combination, so
that any percentile can be estimated. That's many series to answer one
question that one bucket already answers. The cost is that
`histogram_quantile` (the p99) isn't available: add buckets when someone needs
a percentile on a dashboard.

The SLI counts every `POST /payments`, whatever its status. A fast `400` was
answered fast.

## Alerts as code

The rules live in
[observability/prometheus/rules/payment-slo.yml](../../observability/prometheus/rules/payment-slo.yml).
Prometheus loads the directory (`rule_files` in `prometheus.yml`) and
evaluates them every minute.

### Recording rules: the SLIs, per window

```yaml
- record: payment:execution_errors:ratio_rate5m
  expr: sum(rate(payment_executions_total{result="error"}[5m])) / sum(rate(payment_executions_total[5m]))
```

Four windows each (5m, 30m, 1h, 6h), for both SLIs. The name follows
Prometheus's `level:metric:operations` convention. `sum()` adds up every
instance (B7): the SLO belongs to the service, not to an instance. No traffic
gives `0/0`, which is no value, so no alert. Nobody was failed.

### Why not "error rate > 1%"

The obvious rule has two problems:

- **It pages on blips.** One minute of 5% errors at 3am, then nothing.
  That spent a minute's worth of budget, which is nothing over a month. If
  someone gets woken for each one, they learn to ignore the pager.
- **It never fires on slow leaks.** 0.9% errors all month spends nearly
  twice the budget and never crosses 1%.

### Multi-window burn rates

A **burn rate** is how fast the budget goes. A burn rate of 1 spends exactly
the budget in 30 days. 14.4 spends it in about 2 days. The alert is about the
burn rate, not a fixed error rate:

| Window | Burn rate | Error ratio (success / latency) | Budget gone in | Spent before it fires | Severity |
|---|---|---|---|---|---|
| fast: 1h **and** 5m | 14.4x | 7.2% / 14.4% | 2 days | 2% (one hour) | page |
| slow: 6h **and** 30m | 6x | 3% / 6% | 5 days | 5% (six hours) | ticket |

```yaml
- alert: PaymentSuccessBudgetBurn
  expr: payment:execution_errors:ratio_rate1h > 14.4 * 0.005 and payment:execution_errors:ratio_rate5m > 14.4 * 0.005
  labels: {service: payment-service, slo: success, severity: page, window: fast}
```

Each alert has two windows:

- **The long window** (1h) says it's real. A 2-minute spike barely moves a
  one-hour ratio.
- **The short window** (5m) says it's still happening. Without it, the alert
  keeps firing for up to an hour after the problem is fixed, because the 1h
  ratio takes that long to come down.

These are the numbers from Google's
[SRE workbook](https://sre.google/workbook/alerting-on-slos/). Fast and slow
are one alert name with a `window` label, so they share a runbook.

## Cause alerts

SLO alerts are **symptoms**: customers are affected. They don't say why, and
some problems affect no SLO at all. Three cause alerts cover what the SLOs
can't see, each for a reason:

| Alert | Fires when | Why an SLO doesn't cover it |
|---|---|---|
| `PaymentEventsOutboxLag` | `payment_events_pending` hasn't been 0 in 10 minutes **and** has grown over them | Other services aren't told about final statuses (B3). Customers aren't affected yet, so no SLO moves. |
| `CircuitBreakerOpen` | a breaker hasn't been closed for any scrape in 5+ minutes | The SLO says customers are failing. This says which system to call about it. |
| `PaymentsStuckInProcessing` | the oldest `PROCESSING` payment is over 120s old for 5 minutes | The SLO counted the execution once, when the answer was lost. Whether reconciliation (B2) later settles it is invisible to the SLO. |

The details matter:

- **Outbox lag.** "Pending > 0" would fire under any steady load, since the
  relay always has a few rows in flight. "Hasn't emptied **and** is growing"
  means it isn't keeping up. Every instance reads the same count from the
  table, hence `max()`.
- **Circuit breaker.** `state="open" == 1 for 5m` never fires. An open
  breaker lets a trial call through (half-open) after 10s, and opens again
  when it fails, so the series flips between open and half-open and resets
  the `for` clock. "Not closed at any moment in the last minute", held for 5
  minutes, doesn't flip.
- **Stuck payments.** This needs a new gauge,
  `payments_processing_oldest_age_seconds`, read from the table on each
  scrape (`PaymentPersistenceAdapter`, as a `MeterBinder`). Reading it from
  the table rather than from the reconciliation run means it's the same on
  every instance, and it still rises when reconciliation has stopped
  altogether, which is when it matters most. The `(status, status_changed_at)`
  index from B2 answers it. The rule's 120 is the reconciliation
  threshold. It has to be kept in step by hand (see Known shortcuts).

Cause alerts are tickets, never pages. A cause without a symptom (a breaker
open at 3am with no traffic) can wait for the morning. A cause with a symptom
arrives inside the symptom's page, as the next section shows.

## Alertmanager: one page, not five

Prometheus decides what fires. [Alertmanager](../../observability/alertmanager.yml)
decides who hears about it. It's a new container in `docker-compose.yml`, and
Prometheus and Loki's ruler both send to it.

Say the payment system goes down. Without help, that's four notifications:
success burning fast, success burning slow, the payment system's breaker, and
payments stuck in `PROCESSING`. Paging on all of them would wake someone four
times for one problem. With the configuration below, it's one page and one
ticket.

```yaml
route:
  receiver: ticket
  group_by: [service]
  routes:
    - matchers: [severity="page"]
      receiver: page

inhibit_rules:
  - source_matchers: [window="fast"]
    target_matchers: [window="slow"]
    equal: [alertname]
  - source_matchers: [alertname="CircuitBreakerOpen", name="payment-system"]
    target_matchers: [alertname="PaymentsStuckInProcessing"]
    equal: [service]
```

- **Routing.** Only `severity="page"` reaches the pager. Everything else goes
  to the ticket queue.
- **Grouping.** Alerts with the same `service` that fire within `group_wait`
  (30s) go out as one notification, which lists them all.
- **Inhibition.** An alert that is explained by another one is held back
  while the other fires. The fast burn already says the slow one. While the
  payment system's breaker is open, reconciliation can't ask it either, so
  stuck payments are expected.

What's left is one page (success burning fast) and one ticket that names the
payment system's breaker. Alertmanager's UI at `localhost:9093` shows what's
firing, what's inhibited and why.

Locally both receivers are webhooks to the WireMock container
(`wiremock/mappings/alert-receiver.json`), which only records what it was
sent. There's no real pager. In production `page` would be PagerDuty or
Opsgenie and `ticket` a chat channel or an issue tracker.

## A log-based alert: Loki's ruler

```yaml
# observability/loki/rules/payment-logs.yml
- alert: UnknownPaymentSystemStatus
  expr: sum(count_over_time({service_name="payment-service"} |= "Unknown status from payment system" [5m])) > 3
```

Loki's ruler evaluates LogQL like Prometheus evaluates PromQL, and sends to
the same Alertmanager. `loki.yaml` gets a `ruler` section. Loki without
authentication puts everything under the tenant `fake`, so compose mounts the
rules directory at `/etc/loki/rules/fake`.

**When a log alert is right.** When no metric exists, and adding one isn't
worth it. The payment system answering with a status this service doesn't
know (`PaymentExecutionAdapter`) is rare, and it means a contract changed on
their side. The log line is the only trace of it. That's the case B6 named.

**When it's a smell.** When the event is frequent or already counted. Log
queries cost more than metric queries and change whenever someone rewrites a
message, so the line's text becomes a contract nobody sees. Here a comment
on the rule says so. If this alert ever fired often, the right move would be
a counter in the adapter and a Prometheus rule.

`wiremock/mappings/payment-orders.json` gets a stub that answers `ON_HOLD` to
a reference starting with `ON-HOLD`, so the alert can be seen locally (Try
it).

## Runbooks

Every alert has a `runbook_url` annotation pointing at
[docs/runbooks/&lt;alert&gt;.md](../runbooks/README.md), and every one of
those files exists (a test checks). Each says:

- **what it means**, in terms of customers
- **the first three checks**, as queries to paste into Grafana (Prometheus,
  Loki, Tempo) or `psql`
- **then**: what recovers on its own and what needs someone

The person paged at 3am didn't write the rule. The runbook is what they get
instead of the author.

No label or annotation carries an account, an amount or a reference.
Episode 15's rule applies here too, because alerts end up in chat channels
and email.

## Metrics to traces: exemplars

B5 noted that the scrape already carries exemplars. An **exemplar** is one
real observation attached to a bucket: "this request took 0.21s, and here is
its trace ID". Boot adds them whenever tracing has sampled the request, but
only in the OpenMetrics format:

```text
http_server_requests_seconds_bucket{...,method="POST",uri="/payments",le="0.5"} 2 # {span_id="269e4cfa45b80621",trace_id="37275f1c8fddcf7d61e5124f7b02d1f9"} 0.2108 1791658460.007
```

Two things were missing, and now aren't:

- **Prometheus storing them.** Prometheus throws exemplars away unless it
  runs with `--enable-feature=exemplar-storage`, so compose now passes it.
  (Prometheus 3 asks for a format that carries them by default.)
- **Grafana linking them.** The Prometheus data source gets
  `exemplarTraceIdDestinations: trace_id → tempo`. On any graph of
  `http_server_requests_seconds_bucket` with **Exemplars** turned on, each dot
  is a real request. Clicking one opens its trace.

That's the path the latency runbook takes: from "too slow" to one slow
request, to the span that took the time. Only sampled requests have
exemplars: 10% in production (B5) and all of them locally.

## Architecture

```text
application/port/secondary/PaymentMetricsPort         + executionEnded(Execution): what counts as an error is a business fact
application/service/command/ExecutePaymentService     reports it; no other change
infrastructure/observability/PaymentMetrics           payment_executions_total{result}
infrastructure/adapter/secondary/persistence/
   PaymentPersistenceAdapter                          + MeterBinder: payments_processing_oldest_age_seconds
observability/                                        the rules, Alertmanager, Loki's ruler: configuration, not code
```

The SLO lives in two places on purpose. The *classification* (error or not)
is in the application, because only it knows that a limit refusal is a
correct answer. The *objective* (99.5%, the windows, who gets paged) is in
the rule files, because it's an operational decision that changes without a
release.

## What changed

```text
docker-compose.yml                                                + alertmanager; prometheus loads rules, stores exemplars;
                                                                    loki mounts its rules
observability/prometheus.yml                                      + rule_files, alerting
observability/prometheus/rules/payment-slo.yml                    new: SLIs, burn-rate alerts, cause alerts
observability/alertmanager.yml                                    new: routes, grouping, inhibition
observability/loki.yaml                                           + ruler
observability/loki/rules/payment-logs.yml                         new: UnknownPaymentSystemStatus
observability/grafana/datasources/prometheus.yml                  + exemplar link to Tempo
wiremock/mappings/alert-receiver.json                             new: the pager and the ticket queue
wiremock/mappings/payment-orders.json                             + ON-HOLD: a status nobody knows
docs/runbooks/                                                    new: one per alert
src/main/resources/application.properties                         + the 500ms bucket on http.server.requests
src/main/java/com/example/payment/
├── application/
│   ├── port/secondary/PaymentMetricsPort.java                    + executionEnded, Execution
│   └── service/command/ExecutePaymentService.java                reports each execution's result
└── infrastructure/
    ├── observability/PaymentMetrics.java                         + payment.executions
    └── adapter/secondary/persistence/
        ├── PaymentJpaRepository.java                             + oldestProcessingSince
        └── PaymentPersistenceAdapter.java                        + MeterBinder: payments.processing.oldest.age
src/test/resources/prometheus/payment-slo-test.yml                new: promtool test cases
```

## Discuss

- **Symptom vs cause alerts.** Page on symptoms: customers are affected, and
  something has to happen now. Causes go to tickets. They explain a page, or
  they're work for the morning. A system that pages on causes pages for
  things that don't matter (a breaker open at 3am with no traffic) and still
  misses causes nobody thought of. A symptom alert catches those too.
- **Alert fatigue.** Every page that needs no action teaches the person on
  call to ignore pages. Burn-rate windows, grouping and inhibition all exist
  to keep the pager quiet unless it matters. If a page fires and the runbook
  says "wait", the rule is wrong.
- **The error budget as a decision tool.** A budget with room left means the
  team can ship, experiment and take risks. A spent budget means reliability
  work comes first, and risky releases wait until it recovers. That turns
  "are we reliable enough?" from an argument into a number that product and
  engineering agreed on beforehand.

## Known shortcuts

- **No paging integration.** Both receivers are WireMock. A real deployment
  points `page` at PagerDuty or Opsgenie (Alertmanager has receivers for
  both) and `ticket` at a chat channel.
- **No SLO dashboard, no generator.** The rules are written by hand, and no
  Grafana dashboard shows the budget left. [Sloth](https://sloth.dev) or
  [Pyrra](https://github.com/pyrra-dev/pyrra) generate the recording rules,
  burn-rate alerts and dashboards from a short SLO definition.
- **The thresholds are examples.** 99.5%, 500ms, 14.4x and 6x are the
  textbook numbers, not tuned on real traffic. Run the SLOs for a month
  before believing them.
- **The stuck-payment threshold is written twice.** The rule's 120 is
  `payment.reconciliation.threshold` (2m). Changing one means changing the
  other.
- **No "service down" alert.** If every instance stops, the SLO ratios have
  no data and stay quiet. An `absent(up{job="payment-service"} == 1)` alert
  covers that. It's left out because locally one of the two scrape targets is
  always down.
- **The log alert has no unit test.** promtool tests PromQL only. The Loki
  rule was checked by hand (Try it, step 3).

## Try it

```bash
docker compose up -d       # now with Alertmanager on :9093
./mvnw spring-boot:run
```

1. **The rules.** [localhost:9090/rules](http://localhost:9090/rules) lists
   the SLIs and alerts, and [/alerts](http://localhost:9090/alerts) shows
   them all inactive. Loki's: `curl localhost:3100/prometheus/api/v1/rules`.
2. **An exemplar.** Create a few payments ("Episode 01" in
   [http/payments.http](../../http/payments.http)). In Grafana, Explore,
   Prometheus, query
   `http_server_requests_seconds_bucket{uri="/payments",le="0.5"}`, and turn on
   **Exemplars** under Options. Click a dot and choose "Query with Tempo".
3. **A log alert, end to end.** Create and execute 4 payments whose
   reference starts with `ON-HOLD`. Each logs "Unknown status from payment
   system" and stays `PROCESSING` (until reconciliation settles it 2 minutes
   later). Within about 2 minutes (one ruler evaluation, plus Alertmanager's
   30s `group_wait`), Alertmanager shows `UnknownPaymentSystemStatus`, and
   WireMock received it:
   ```bash
   curl -s -X POST localhost:8089/__admin/requests/find -d '{"method":"POST","urlPathPattern":"/alerts/.*"}'
   ```
   It arrived at `/alerts/ticket`, grouped by `service`, with the runbook URL.
4. **The SLI.** `curl localhost:8080/actuator/prometheus | grep payment_executions`:
   the four `ON-HOLD` executions are `error`, and the others are `completed`.

## Tests

| Test | Change |
|---|---|
| `AlertRulesTest` | **new, 2.** `promtool test rules` in the Prometheus image compose runs, against [src/test/resources/prometheus/payment-slo-test.yml](../../src/test/resources/prometheus/payment-slo-test.yml). Healthy traffic (0.08% errors, 20% refusals, exactly 1% slow, a busy outbox that keeps up, lost answers settled within 3 minutes): nothing fires. Payment system down, over two instances: success fast and slow, its breaker, stuck payments; latency quiet. A 4% slow leak: the slow ticket only. Slow creation: both latency alerts. Outbox lag: quiet at 9 minutes, firing at 20. Checked by breaking the rules: a wrong burn-rate factor and a wrong stuck threshold each fail it. Second test: every `- alert:` in both rule files has a `runbook_url`, and the file it names exists. |
| `ExecutePaymentServiceTest` | **+1, and assertions in 7.** Completed → `COMPLETED`; rejected, declined and over the limit → `REFUSED`; unknown outcome and a system down → `ERROR`; executing twice counts once; an unknown payment isn't counted. |
| `PaymentEndToEndTest` | **+1.** The OpenMetrics scrape has the `le="0.5"` bucket of `POST /payments` with an exemplar (`trace_id`, `span_id`), `payment_executions_total`, `payments_processing_oldest_age_seconds` and `payment_events_pending`. |

247 tests, passing.
