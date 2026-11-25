-- What the operations agent proposed and what people decided (docs/episodes/bonus-15). The id is the graph's thread
-- id in lg4jcheckpoint. Exactly once lives here, not in the checkpoints: the move from PROPOSED to APPROVED is a
-- compare-and-set, like a payment's status, so of two approvals only one runs the action. So does the record of what
-- the operators saw (findings): checkpoints exist to resume a run, and the library releases them when it ends.
CREATE TABLE investigation (
    id              UUID         PRIMARY KEY,
    payment_id      UUID         NOT NULL,
    started_by      VARCHAR(255) NOT NULL,
    started_at      TIMESTAMPTZ  NOT NULL,
    proposed_action VARCHAR(40)  NOT NULL,
    escalation_text TEXT,
    -- The evidence, diagnosis, allowed actions and proposal, as JSON written by the agent adapter.
    findings        TEXT         NOT NULL,
    -- PROPOSED -> APPROVED -> EXECUTED | NO_LONGER_NEEDED, or PROPOSED -> REJECTED
    status          VARCHAR(20)  NOT NULL,
    decided_by      VARCHAR(255),
    decided_at      TIMESTAMPTZ,
    result          TEXT,
    finished_at     TIMESTAMPTZ
);
