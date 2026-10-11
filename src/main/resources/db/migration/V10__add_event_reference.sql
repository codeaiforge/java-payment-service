-- Versioned event schemas (docs/episodes/bonus-10). Payment events now carry the payment's reference, and the statement
-- shows it. Nullable in both: outbox rows written before this migration have none, and neither do the events already
-- on payment-events, which a rebuild replays into the statement. 140 characters, as PaymentReference allows.
ALTER TABLE payment_outbox ADD COLUMN reference VARCHAR(140);
ALTER TABLE statement_line ADD COLUMN reference VARCHAR(140);
