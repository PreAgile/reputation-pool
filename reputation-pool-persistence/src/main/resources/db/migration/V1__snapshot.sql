-- Whole-pool snapshot, stored relationally so the module needs no JSON library (plain JDBC only).
--
-- The unit of persistence is the entire PoolSnapshot: cells (+ their outcome windows), the blocklist,
-- and the registered set, replaced together in one transaction. snapshot_meta is a single-row marker
-- that distinguishes "never saved" (first run -> load() empty) from "saved an empty pool".

-- One row per (resource x context) reputation cell. Instants (cooldown_until, updated_at) are stored
-- as epoch-nanosecond bigint rather than timestamptz: timestamptz is microsecond-capped and would
-- silently truncate the domain's nanosecond-precision Instants, so bigint epoch-nanos is chosen for a
-- lossless round-trip. cooldown_until uses the domain's Instant.EPOCH "not cooling" sentinel, which is
-- epoch-nanos 0.
CREATE TABLE cell (
    resource_kind         text NOT NULL,
    resource_value        text NOT NULL,
    context               text NOT NULL,
    score                 double precision NOT NULL,
    consecutive_failures  int NOT NULL,
    consecutive_successes int NOT NULL,
    state                 text NOT NULL,
    cooldown_until        bigint NOT NULL,
    updated_at            bigint NOT NULL,
    PRIMARY KEY (resource_kind, resource_value, context)
);

-- The cell's bounded outcome window, one row per Outcome, ordered by ordinal. A child table (rather
-- than jsonb) keeps the module on pure relational JDBC with no JSON dependency. failure_type is NULL
-- for a Success and the FailureType enum name for a Failure. latency_ns stores the outcome latency in
-- nanoseconds; storing whole milliseconds would truncate sub-millisecond latencies. The row is deleted
-- with its parent cell (ON DELETE CASCADE) so the whole-replace save never leaves orphaned outcomes.
CREATE TABLE cell_outcome (
    resource_kind  text NOT NULL,
    resource_value text NOT NULL,
    context        text NOT NULL,
    ordinal        int NOT NULL,
    success        boolean NOT NULL,
    failure_type   text,
    latency_ns     bigint NOT NULL,
    PRIMARY KEY (resource_kind, resource_value, context, ordinal),
    FOREIGN KEY (resource_kind, resource_value, context)
        REFERENCES cell (resource_kind, resource_value, context) ON DELETE CASCADE
);

-- The blocklist. A PERMANENT block is Instant.MAX in the domain, which does not fit an epoch-nanos
-- bigint, so permanent is represented as `until NULL`; a finite block stores its expiry as
-- epoch-nanosecond bigint (chosen over timestamptz for the same lossless round-trip reason as above).
CREATE TABLE blocklist_entry (
    resource_kind  text NOT NULL,
    resource_value text NOT NULL,
    until          bigint,
    PRIMARY KEY (resource_kind, resource_value)
);

-- The set of resources eligible to be lent at all.
CREATE TABLE registered_resource (
    resource_kind  text NOT NULL,
    resource_value text NOT NULL,
    PRIMARY KEY (resource_kind, resource_value)
);

-- Single-row marker (id is pinned to 1). Its presence means a snapshot was saved; its absence means
-- first run. saved_at records when the last checkpoint was written.
CREATE TABLE snapshot_meta (
    id       int PRIMARY KEY CHECK (id = 1),
    saved_at timestamptz NOT NULL
);
