-- Namespace every snapshot table by pool_id, so one PostgreSQL schema can hold many independent pools
-- (one per tenant) instead of a single global pool.
--
-- Before this, the four state tables (cell, cell_outcome, blocklist_entry, registered_resource) and the
-- single-row snapshot_meta marker held exactly one pool's state: save() whole-replaced every row, and
-- snapshot_meta pinned id = 1. A multi-tenant host needs each pool's rows kept apart so one tenant's
-- checkpoint never overwrites another's. The change is additive: every existing row is backfilled to the
-- 'default' pool, and a store that never sets a pool id keeps behaving exactly as before (the column
-- default is 'default', and the reference server is wired to pool id 'default').
--
-- Each primary key is redefined to LEAD with pool_id, so (pool_id, <old key>) is the new identity and
-- rows of different pools can share the same resource/context. The cell_outcome -> cell foreign key is
-- rebuilt to include pool_id, keeping its ON DELETE CASCADE per pool. snapshot_meta drops its
-- single-row CHECK (id = 1) marker for a per-pool marker keyed by pool_id.

-- Add the namespacing column. NOT NULL DEFAULT 'default' backfills every existing row to the default
-- pool in one step, and keeps 'default' as the value a pool-unaware caller's inserts land under.
ALTER TABLE cell ADD COLUMN pool_id text NOT NULL DEFAULT 'default';
ALTER TABLE cell_outcome ADD COLUMN pool_id text NOT NULL DEFAULT 'default';
ALTER TABLE blocklist_entry ADD COLUMN pool_id text NOT NULL DEFAULT 'default';
ALTER TABLE registered_resource ADD COLUMN pool_id text NOT NULL DEFAULT 'default';
ALTER TABLE snapshot_meta ADD COLUMN pool_id text NOT NULL DEFAULT 'default';

-- Drop the cell_outcome -> cell foreign key before either primary key is redefined (its name is
-- auto-generated, so it is looked up rather than guessed). It is recreated below over the new key.
DO $$
DECLARE
    fk_name text;
BEGIN
    FOR fk_name IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'cell_outcome'::regclass AND contype = 'f'
    LOOP
        EXECUTE 'ALTER TABLE cell_outcome DROP CONSTRAINT ' || quote_ident(fk_name);
    END LOOP;
END $$;

-- Redefine every primary key to lead with pool_id (constraint names are the deterministic
-- <table>_pkey Postgres generates).
ALTER TABLE cell DROP CONSTRAINT cell_pkey;
ALTER TABLE cell ADD PRIMARY KEY (pool_id, resource_kind, resource_value, context);

ALTER TABLE cell_outcome DROP CONSTRAINT cell_outcome_pkey;
ALTER TABLE cell_outcome ADD PRIMARY KEY (pool_id, resource_kind, resource_value, context, ordinal);

ALTER TABLE blocklist_entry DROP CONSTRAINT blocklist_entry_pkey;
ALTER TABLE blocklist_entry ADD PRIMARY KEY (pool_id, resource_kind, resource_value);

ALTER TABLE registered_resource DROP CONSTRAINT registered_resource_pkey;
ALTER TABLE registered_resource ADD PRIMARY KEY (pool_id, resource_kind, resource_value);

-- Recreate the cascade FK over the pool-scoped key, so deleting a pool's cell still cascades to that
-- pool's outcome rows (the whole-replace save leans on this).
ALTER TABLE cell_outcome
    ADD FOREIGN KEY (pool_id, resource_kind, resource_value, context)
    REFERENCES cell (pool_id, resource_kind, resource_value, context) ON DELETE CASCADE;

-- snapshot_meta was a single global marker (id int PRIMARY KEY CHECK (id = 1)). Drop the id column —
-- which drops the CHECK and the old primary key with it — and key the marker by pool_id, so each pool
-- has its own "a snapshot was saved" marker distinguishing its first run from a saved empty pool.
ALTER TABLE snapshot_meta DROP COLUMN id;
ALTER TABLE snapshot_meta ADD PRIMARY KEY (pool_id);
