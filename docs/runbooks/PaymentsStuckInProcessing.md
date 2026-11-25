# PaymentsStuckInProcessing

**What it means.** A payment has been `PROCESSING` for more than 7 minutes. Reconciliation (B2) should settle one
within the 2-minute threshold plus one 1-minute run. Either it can't get an answer, or it isn't running.

The money may or may not have moved. The payment can't be executed again, so nothing can pay twice, but the
customer doesn't know the outcome.

## First three checks

1. **Is reconciliation running?** `GET /operations/reconciliation` with an operator token (scope
   `payments.operate`). `lastRunStartedAt` should move about once a minute (B7); it marks a start, not a success.
   `runningOn` names the instance holding the lock, or is null when none does. If `lastRunStartedAt` is old and
   `runningOn` stays set, that instance holds a lock it isn't using: `runningUntil` says when it expires. Without the
   service, PostgreSQL: `SELECT * FROM shedlock WHERE name = 'reconciliation';` (`locked_at`, `locked_by`,
   `lock_until`).
2. **What does it hear back?** `sum by (result) (rate(payments_reconciled_total[10m]))`. Only `unknown`: the payment
   system isn't answering the question either. Loki: `{service_name="payment-service"} |= "not reconciled"`.
3. **Which payments?** Find the oldest, then settle it now: `POST /operations/payments/{id}/reconcile` with an
   operator token (scope `payments.operate`). The answer says what the payment system said and whether the payment
   moved. `UNKNOWN`: the payment system has no answer either, so ask its owners about it by payment ID (it is the
   idempotency key).
   ```sql
   SELECT id, status_changed_at FROM payment WHERE status = 'PROCESSING' ORDER BY status_changed_at;
   ```

The gauge is `payments_processing_oldest_age_seconds`. The rule's 120 must match `payment.reconciliation.threshold`.
