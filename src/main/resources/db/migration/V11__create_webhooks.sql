-- Webhooks (docs/episodes/bonus-11). A customer's endpoint, told about their payments' events.
-- SHORTCUT: the secret is stored as it is, so whoever can read this table can sign as us. It is never returned after
-- registration and never logged; encrypt it with a key from a key management service before this holds real secrets.
CREATE TABLE webhook_subscription (
    id                   UUID          PRIMARY KEY,
    customer_id          VARCHAR(64)   NOT NULL,
    url                  VARCHAR(2048) NOT NULL,
    secret               VARCHAR(100)  NOT NULL,
    status               VARCHAR(20)   NOT NULL,
    consecutive_failures INTEGER       NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL
);

CREATE INDEX webhook_subscription_customer ON webhook_subscription (customer_id);

-- One row per event per subscription: the work of getting it there, kept across restarts and retries. The row holds
-- the event, not the HTTP body: the sender owns the public contract. Deleting a subscription deletes its deliveries.
-- next_attempt_at is when it is due, or, while an instance is sending it, when that instance's lease ends.
-- SHORTCUT: DELIVERED and FAILED rows are kept forever; delete them after a retention period, as the outbox does.
CREATE TABLE webhook_delivery (
    id                     UUID           PRIMARY KEY,
    subscription_id        UUID           NOT NULL REFERENCES webhook_subscription (id) ON DELETE CASCADE,
    event_id               UUID           NOT NULL,
    event_type             VARCHAR(40)    NOT NULL,
    payment_id             UUID           NOT NULL,
    refund_id              UUID,
    source_account_id      VARCHAR(64)    NOT NULL,
    destination_account_id VARCHAR(64)    NOT NULL,
    amount                 NUMERIC(19, 2) NOT NULL,
    currency               VARCHAR(3)     NOT NULL,
    reference              VARCHAR(140),
    occurred_at            TIMESTAMPTZ    NOT NULL,
    status                 VARCHAR(20)    NOT NULL,
    attempts               INTEGER        NOT NULL,
    next_attempt_at        TIMESTAMPTZ    NOT NULL,
    last_response_status   INTEGER,
    created_at             TIMESTAMPTZ    NOT NULL,
    -- Kafka delivers an event at least once; it is queued once per subscription.
    UNIQUE (subscription_id, event_id)
);

-- The dispatcher only ever looks for pending deliveries that are due; the others drop out of the index.
CREATE INDEX webhook_delivery_due ON webhook_delivery (next_attempt_at) WHERE status = 'PENDING';
