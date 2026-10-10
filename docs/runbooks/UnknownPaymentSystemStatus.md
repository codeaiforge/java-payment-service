# UnknownPaymentSystemStatus

**What it means.** More than 3 times in 5 minutes the payment system answered with a status this service doesn't
know (not `SETTLED` or `REJECTED`). Each payment is left `PROCESSING`, since the money may have moved
(`PaymentExecutionAdapter`). No metric counts this: the alert comes from the log line, through Loki's ruler.

Most likely the payment system has added a status, a contract change nobody told us about.

## First three checks

1. **Which status?** Loki: `{service_name="payment-service"} |= "Unknown status from payment system"`. The status is
   at the end of each line.
2. **Since when?** The same query as a graph. A sudden start points at a release on their side.
   ```logql
   sum(count_over_time({service_name="payment-service"} |= "Unknown status from payment system" [5m]))
   ```
3. **One request, as a trace.** "View trace" on a line opens it in Tempo, with the call to `/payment-orders`.

## Then

Ask the payment system's owners what the status means. Reconciliation asks again after 2 minutes and settles
payments it gets a known answer for. Teaching the adapter the new status is a code change.
