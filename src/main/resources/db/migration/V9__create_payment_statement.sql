-- The payment statement (docs/episodes/bonus-09): a read model, built only from the payment-events topic, never written
-- by the write side and never joined to its tables. No foreign keys to payment: it can be emptied and rebuilt alone.
-- One line per payment, from the source account's side. amount, status and settled_at are null on a line a refund
-- event created before its payment's event arrived; the statement leaves such a line out until they are filled.
CREATE TABLE statement_line (
    payment_id                UUID           PRIMARY KEY,
    account_id                VARCHAR(64)    NOT NULL,
    counterparty_account_id   VARCHAR(64)    NOT NULL,
    amount                    NUMERIC(19, 2),
    currency                  VARCHAR(3)     NOT NULL,
    status                    VARCHAR(20),
    refunded_amount           NUMERIC(19, 2) NOT NULL DEFAULT 0,
    settled_at                TIMESTAMPTZ,
    last_event_id             UUID           NOT NULL
);

-- The statement's one query: an account's lines, newest first.
CREATE INDEX statement_line_account_settled_at ON statement_line (account_id, settled_at DESC);

-- Every event the statement has applied. Written in the same transaction as the line, so an event is applied once
-- however often Kafka delivers it.
CREATE TABLE statement_processed_event (
    event_id     UUID         PRIMARY KEY,
    processed_at TIMESTAMPTZ  NOT NULL
);
