-- Whole-pool snapshot, stored relationally so the module needs no JSON library (plain JDBC only).
--
-- The unit of persistence is the entire PoolSnapshot: cells (+ their outcome windows), the blocklist,
-- and the registered set, replaced together in one transaction. snapshot_meta is a single-row marker
-- that distinguishes "never saved" (first run -> load() empty) from "saved an empty pool".

-- One row per (resource x context) reputation cell. cooldown_until uses the domain's Instant.EPOCH
-- "not cooling" sentinel, which stores fine as a timestamptz.
CREATE TABLE cell (
    resource_kind         text NOT NULL,
    resource_value        text NOT NULL,
    context               text NOT NULL,
    score                 double precision NOT NULL,
    consecutive_failures  int NOT NULL,
    consecutive_successes int NOT NULL,
    state                 text NOT NULL,
    cooldown_until        timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    PRIMARY KEY (resource_kind, resource_value, context)
);

-- The cell's bounded outcome window, one row per Outcome, ordered by ordinal. A child table (rather
-- than jsonb) keeps the module on pure relational JDBC with no JSON dependency. failure_type is NULL
-- for a Success and the FailureType enum name for a Failure.
CREATE TABLE cell_outcome (
    resource_kind  text NOT NULL,
    resource_value text NOT NULL,
    context        text NOT NULL,
    ordinal        int NOT NULL,
    success        boolean NOT NULL,
    failure_type   text,
    latency_ms     bigint NOT NULL,
    PRIMARY KEY (resource_kind, resource_value, context, ordinal)
);

-- The blocklist. A PERMANENT block is Instant.MAX in the domain, which does not fit timestamptz, so
-- permanent is represented as `until NULL`; a finite block stores its expiry as a timestamptz.
CREATE TABLE blocklist_entry (
    resource_kind  text NOT NULL,
    resource_value text NOT NULL,
    until          timestamptz,
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
