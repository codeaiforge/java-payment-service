-- Rate limits per customer (docs/episodes/bonus-12). One token bucket per customer and limit, shared by every instance.
-- Bucket4j's own layout: id is the limit and the customer (requests:20/PT1S:CUST-1001), state is the bucket, serialized
-- by Bucket4j. Each request locks its row (SELECT ... FOR UPDATE), takes a token and writes the row back.
-- SHORTCUT: rows are never deleted: one per customer and limit, plus the old ones after a limit changes. Delete rows not
-- updated for a day (a full bucket again) on a schedule once the table grows enough to matter.
CREATE TABLE rate_limit_bucket (
    id    VARCHAR(200) PRIMARY KEY,
    state BYTEA
);
