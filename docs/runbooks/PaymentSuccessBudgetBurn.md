# PaymentSuccessBudgetBurn

**What it means.** More payment executions than the SLO allows ended without an answer: an external system was
down, the payment system's answer was lost (the payment is left `PROCESSING`), or the service itself failed. A
business "no" (not authorized, over the limit, rejected) is not counted. `window="fast"` pages: at this rate the
month's 0.5% error budget is gone in about 2 days. `window="slow"` is a ticket: about 5 days.

Customers are affected now: their payments are neither done nor refused.

## First three checks

1. **Which cause is firing with it?** The same notification lists every alert firing for the service (Alertmanager
   groups them, [http://localhost:9093](http://localhost:9093)). `CircuitBreakerOpen` names the system that is down.
2. **Which system is failing?** Prometheus:
   `sum by (system) (rate(external_payment_errors_total[5m]))` and `rate(external_account_errors_total[5m])`.
   Nothing there: look at the service's own errors in Loki:
   `{service_name="payment-service"} | severity_text="ERROR"`.
3. **One failed execution, end to end.** Loki: `{service_name="payment-service"} |= "outcome unknown"`. Each line
   has a `trace_id`; "View trace" opens it in Tempo, with the call that failed.

## Then

Payments left `PROCESSING` are settled by reconciliation once the payment system answers again (B2). Nothing to do
for them unless [PaymentsStuckInProcessing](PaymentsStuckInProcessing.md) fires. Executions refused while a system
was down stay `CREATED` or `AUTHORIZED`; the customer can execute them again.

SLI: `payment:execution_errors:ratio_rate1h` (and `5m`, `30m`, `6h`).
