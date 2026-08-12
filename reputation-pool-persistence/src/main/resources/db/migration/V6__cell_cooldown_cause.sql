-- Persist the failure type that put a cell into its current cooldown.
--
-- The pool decides which recovery mechanism owns a cooled cell — a half-open real-traffic trial, or a
-- synthetic probe — from the cause of the cooldown: only a site block (BLOCKED) needs real traffic to
-- judge it. That cause used to be re-derived by scanning the persisted outcome window backwards for the
-- newest failure, which is not the same value: failures reported while a cell is already COOLING land
-- in the window without resizing the cooldown, and a full window eventually evicts the causing failure
-- altogether. The cause is now recorded on the cell when the cooldown is set, so it cannot drift.
--
-- NULL means "this cell has never cooled", the same "no value here" convention cell_outcome.failure_type
-- already uses for a Success. The column holds the FailureType enum name, as cell.state and
-- cell_outcome.failure_type do for their enums — a text name survives an enum gaining a constant, which
-- an ordinal would not.
ALTER TABLE cell ADD COLUMN cooldown_cause text;

-- Backfill from the window, so an existing checkpoint keeps the exact ownership decision it had before
-- this migration: the newest failure in the cell's window is precisely what the old scan read. Rows that
-- are not COOLING are left NULL — the column is only ever read for a cooling cell, and inventing a cause
-- for the others would just be noise. A COOLING cell whose window holds no failure at all also stays
-- NULL, which reads as "not a block": the conservative side of the split, exactly as the old scan's
-- empty-window branch behaved.
UPDATE cell c
SET cooldown_cause = (
    SELECT o.failure_type
    FROM cell_outcome o
    WHERE o.pool_id = c.pool_id
      AND o.resource_kind = c.resource_kind
      AND o.resource_value = c.resource_value
      AND o.context = c.context
      AND o.success = false
    ORDER BY o.ordinal DESC
    LIMIT 1)
WHERE c.state = 'COOLING';
