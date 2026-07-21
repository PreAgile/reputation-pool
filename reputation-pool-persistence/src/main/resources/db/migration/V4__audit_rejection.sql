-- Let the audit trail hold a resource-less event: the new AcquisitionRejected PoolEvent records that an
-- acquire for a context found nothing to lend, so it names a context but no resource.
--
-- V2 made resource_kind/resource_value NOT NULL because every event so far carried a resource. This is
-- the first case that does not, so those two columns join context/until/cause as "NULL when the case
-- does not carry the field" — the wide-table shape the audit_event comment already describes. The
-- change is additive and backward-compatible: every existing row keeps its (non-null) resource, and a
-- resourced event still inserts exactly as before.
ALTER TABLE audit_event ALTER COLUMN resource_kind DROP NOT NULL;
ALTER TABLE audit_event ALTER COLUMN resource_value DROP NOT NULL;
