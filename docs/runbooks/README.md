# Runbooks

One per alert in [observability/prometheus/rules](../../observability/prometheus/rules/payment-slo.yml) and
[observability/loki/rules](../../observability/loki/rules/payment-logs.yml). Each alert's `runbook_url` points at
its page here ([Bonus Episode 08](../episodes/bonus-08-alerting-slos.md)).

| Alert | Kind | Severity |
|---|---|---|
| [PaymentSuccessBudgetBurn](PaymentSuccessBudgetBurn.md) | SLO: executions that got no answer | page (fast) · ticket (slow) |
| [PaymentLatencyBudgetBurn](PaymentLatencyBudgetBurn.md) | SLO: `POST /payments` slower than 500ms | page (fast) · ticket (slow) |
| [CircuitBreakerOpen](CircuitBreakerOpen.md) | cause: an external system is down | ticket |
| [PaymentsStuckInProcessing](PaymentsStuckInProcessing.md) | cause: reconciliation isn't settling | ticket |
| [PaymentEventsOutboxLag](PaymentEventsOutboxLag.md) | cause: events aren't reaching Kafka | ticket |
| [UnknownPaymentSystemStatus](UnknownPaymentSystemStatus.md) | cause, from logs: an answer we can't read | ticket |

The links below point at the local stack (`docker compose up`). In Grafana, open
[Explore](http://localhost:3000/explore), pick the data source, and paste the query.
