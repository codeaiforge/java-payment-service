# CircuitBreakerOpen

**What it means.** The circuit breaker for the external system in the `name` label (`account-system`,
`authorization-system`, `limit-system` or `payment-system`) hasn't been closed for 5 minutes. At least half its
recent calls failed, so calls to it now fail at once without being made (Episode 16).

This is a cause. If customers are affected, an SLO alert pages as well; this one tells you why.

## First three checks

1. **Is the system really down?** Loki: `{service_name="payment-service"} |= "unavailable"` or
   `|= "gave no answer"`. Timeouts and 5xx open the breaker; a 4xx doesn't.
2. **Is it us?** Every instance shows the same: `resilience4j_circuitbreaker_state{state="closed"}` per `instance`.
   One instance alone points at its network or configuration, not the system.
3. **Tell the system's owners.** The service can't fix it. Locally, the "system" is WireMock:
   [http://localhost:8089/__admin/mappings](http://localhost:8089/__admin/mappings).

## Then

The breaker closes on its own once trial calls succeed. With no traffic it stays open (no trial call is made), and
so does this alert: send one request, or wait.

While the payment system's breaker is open, Alertmanager holds back
[PaymentsStuckInProcessing](PaymentsStuckInProcessing.md): reconciliation can't ask either.
