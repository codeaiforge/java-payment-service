-- The operations agent's paused investigations (docs/episodes/bonus-15). LangGraph4j's PostgreSQL saver keeps each
-- graph run's checkpoints here, so a run paused before its action resumes after a restart or on another instance.
-- The layout is the saver's own (langgraph4j-postgres-saver 1.9.10, db/migration/v2.1__init.sql), created here and not
-- by the library at start-up: the schema has one owner, and the change is an expansion the previous release ignores.
CREATE TABLE lg4jthread (
    thread_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    thread_name      VARCHAR(255) UNIQUE NOT NULL,
    parent_thread_id BIGINT,
    is_interrupted   BOOLEAN DEFAULT FALSE NOT NULL,
    message          TEXT,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE lg4jthreadtag (
    thread_id        BIGINT PRIMARY KEY,
    thread_name      VARCHAR(255),
    released_version INTEGER,
    parent_thread_id BIGINT,
    is_released      BOOLEAN DEFAULT FALSE NOT NULL,
    is_error         BOOLEAN DEFAULT FALSE NOT NULL,
    message          TEXT,
    created_at       TIMESTAMP WITH TIME ZONE
);

CREATE TABLE lg4jcheckpoint (
    checkpoint_id        UUID PRIMARY KEY,
    parent_checkpoint_id UUID,
    thread_id            BIGINT NOT NULL,
    node_id              VARCHAR(255),
    next_node_id         VARCHAR(255),
    state_data           TEXT NOT NULL,
    state_content_type   VARCHAR(100) NOT NULL,
    saved_at             TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg4jcheckpoint_thread_id ON lg4jcheckpoint (thread_id);
CREATE INDEX idx_lg4jcheckpoint_thread_id_saved_at_desc ON lg4jcheckpoint (thread_id, saved_at DESC);
CREATE INDEX idx_lg4jthreadtag_thread_name_released_version ON lg4jthreadtag (thread_name, released_version DESC);
