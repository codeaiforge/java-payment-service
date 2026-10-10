# PaymentEventsOutboxLag

**What it means.** The `payment_outbox` table hasn't been empty for 10 minutes and has more unpublished rows than
10 minutes ago. Final statuses are stored, but other services aren't hearing about them on `payment-events` (B3).
Payments themselves are fine; nothing a customer does is blocked.

## First three checks

1. **Is Kafka up?** Loki: `{service_name="payment-service"} |= "not published"`. The relay logs every failed send.
2. **Is the relay running?** It runs every second on every instance. A trace per run, in Tempo: search for span name
   `payment-events relay`. None recent: the scheduler is stuck or the instances are down.
3. **Is one payment holding the rest?** A payment's events go in order (B7), so one row that always fails holds
   back that payment's later rows.
   ```sql
   SELECT payment_id, count(*) FROM payment_outbox WHERE published_at IS NULL GROUP BY payment_id ORDER BY 2 DESC;
   ```

## Then

Once sends succeed the relay catches up on its own, 100 rows per run. Consumers deduplicate by `eventId`, so rows
sent twice during the outage are harmless.

The gauge is `payment_events_pending`.
