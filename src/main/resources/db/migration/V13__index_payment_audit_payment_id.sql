-- A payment's history (GET /payments/{id}/history) reads its audit rows by payment_id. PostgreSQL doesn't index a
-- foreign key by itself, so without this every read scans the whole, ever-growing table.
CREATE INDEX payment_audit_payment_id ON payment_audit (payment_id, id);
