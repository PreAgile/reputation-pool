-- Namespace the audit trail by pool_id, so one PostgreSQL schema can hold many independent pools'
-- histories (one per tenant) instead of a single global ledger — the append-only counterpart to V3's
-- namespacing of the snapshot tables.
--
-- The change is additive, exactly like V3: a single column with NOT NULL DEFAULT 'default' backfills
-- every existing row to the default pool in one step, and keeps 'default' as the value a pool-unaware
-- caller's inserts land under (the reference server, and PostgresAuditTrail.emit, still write to
-- 'default'). No primary key is redefined: audit_event's identity is its append-only IDENTITY seq, a
-- surrogate that is already globally unique across pools, so pool_id joins the wide table as another
-- descriptive column, not part of the key.
ALTER TABLE audit_event ADD COLUMN pool_id text NOT NULL DEFAULT 'default';

-- The tenant-scoped read path — cloud's AuditEventReader pages one pool's history with a keyset seek
-- (WHERE pool_id = ? AND seq < ? ORDER BY seq DESC) — would otherwise scan the whole shared ledger and
-- filter. This composite index makes that seek an index range walk: pool_id narrows to the tenant, seq
-- orders (and bounds) within it.
CREATE INDEX audit_event_pool_seq_idx ON audit_event (pool_id, seq);
