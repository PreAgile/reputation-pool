-- Append-only audit trail: one row per PoolEvent, in emission order, never updated or deleted.
--
-- Where the snapshot tables (V1) are whole-replaced on every checkpoint and answer "what is the state
-- now", this ledger only ever grows and answers "what happened, and why". A single wide table with an
-- event_type discriminator holds every case of the sealed PoolEvent; columns a case does not carry are
-- NULL, the same shape as cell_outcome's nullable failure_type.
--
-- seq gives a total order even for events stamped with the same instant. occurred_at is the event's
-- own at() as epoch-nanosecond bigint (timestamptz is microsecond-capped, the V1 decision). until is
-- NULL both when the case has no deadline and for a permanent block (Instant.MAX, the blocklist_entry
-- convention); the event_type disambiguates. cause is the FailureType name, RESOURCE_COOLED only.
--
-- The table grows without bound by design; retention/pruning is deliberately deferred.
CREATE TABLE audit_event (
    seq            bigserial PRIMARY KEY,
    event_type     text NOT NULL,
    resource_kind  text NOT NULL,
    resource_value text NOT NULL,
    context        text,
    occurred_at    bigint NOT NULL,
    until          bigint,
    cause          text
);
