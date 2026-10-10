# PaymentsStuckInProcessing

**What it means.** A payment has been `PROCESSING` for more than 7 minutes. Reconciliation (B2) should settle one
within the 2-minute threshold plus one 1-minute run. Either it can't get an answer, or it isn't running.

The money may or may not have moved. The payment can't be executed again, so nothing can pay twice, but the
customer doesn't know the outcome.

## First three checks

1. **Is reconciliation running?** PostgreSQL: `SELECT * FROM shedlock WHERE name = 'reconciliation';`
   `locked_at` should move about once a minute (B7). If it doesn't, an instance may hold a lock it isn't using:
   `lock_until` says when it expires.
2. **What does it hear back?** `sum by (result) (rate(payments_reconciled_total[10m]))`. Only `unknown`: the payment
   system isn't answering the question either. Loki: `{service_name="payment-service"} |= "not reconciled"`.
3. **Which payments?** Ask the payment system's owners about the oldest, by payment ID (it is the idempotency key).
   ```sql
   SELECT id, status_changed_at FROM payment WHERE status = 'PROCESSING' ORDER BY status_changed_at;
   ```

The gauge is `payments_processing_oldest_age_seconds`. The rule's 120 must match `payment.reconciliation.threshold`.
