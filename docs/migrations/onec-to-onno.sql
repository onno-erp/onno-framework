-- ============================================================================
-- onec → onno: existing-database migration
-- ============================================================================
-- Renames the framework's internal infrastructure tables and indexes from the
-- `onec_` prefix to `onno_`. Run this ONCE against each existing consumer
-- database, with the application stopped, BEFORE deploying the onno release.
--
-- Why it's needed
-- ---------------
-- Business tables (catalogs/documents/registers) are named after your entities
-- and are NOT affected. Only these framework-internal tables carry the prefix.
-- On the onno release the framework looks for `onno_*`; if the old `onec_*`
-- tables are still present it does NOT drop them (destructive changes are gated
-- off by default), but it creates fresh EMPTY `onno_*` tables instead — which
-- silently orphans your data:
--   * onno_sequences  → document numbering counters reset
--   * onno_comments   → existing comments disappear
--   * onno_inbox      → Kafka idempotency lost (possible reprocessing)
--   * onno_mail_*     → mail outbox/suppression history lost
-- Renaming the tables in place (below) avoids all of that.
--
-- schema_history / diff engine
-- ----------------------------
-- No snapshot surgery is required. SchemaDiffEngine only proposes dropping a
-- table when it is BOTH absent from the new model AND still present in the live
-- DB. After this rename the old `onec_*` names no longer exist in the DB, so no
-- drops are proposed and the matching `onno_*` tables already exist, so nothing
-- is created — the plan is empty. On the first boot with onno.schema.mode=apply
-- the framework re-records a fresh `onno_*` snapshot automatically.
--
-- Procedure (per consumer DB)
-- ---------------------------
--   1. Stop the old (onec) application.
--   2. Back up the database.
--   3. Run this script (psql -f, H2 RunScript, or your migration tool).
--   4. Deploy + start the onno release (default onno.schema.mode=apply).
--   5. Verify startup logs show 0 schema changes applied.
--
-- Portability
-- -----------
-- Works as-is on PostgreSQL and H2 2.x. `IF EXISTS` makes every statement a
-- no-op when the table/index isn't present, so you can run the whole script
-- regardless of which starters you use (e.g. no Kafka → onno_inbox is skipped).
-- ----------------------------------------------------------------------------

-- --- Tables -----------------------------------------------------------------
ALTER TABLE IF EXISTS onec_schema_history   RENAME TO onno_schema_history;   -- onno-framework (migration ledger)
ALTER TABLE IF EXISTS onec_sequences        RENAME TO onno_sequences;        -- onno-framework (numbering counters)
ALTER TABLE IF EXISTS onec_outbox           RENAME TO onno_outbox;           -- onno-framework (event outbox)
ALTER TABLE IF EXISTS onec_inbox            RENAME TO onno_inbox;            -- onno-kafka-starter (consumer idempotency)
ALTER TABLE IF EXISTS onec_comments         RENAME TO onno_comments;         -- onno-ui-starter
ALTER TABLE IF EXISTS onec_mail_outbox      RENAME TO onno_mail_outbox;      -- onno-mail-starter
ALTER TABLE IF EXISTS onec_mail_suppression RENAME TO onno_mail_suppression; -- onno-mail-starter

-- --- Indexes ----------------------------------------------------------------
-- The framework re-asserts these every boot via CREATE INDEX IF NOT EXISTS. If
-- left under the old name you'd end up with a duplicate index per table after
-- the first boot; renaming keeps a single index.
ALTER INDEX IF EXISTS idx_onec_outbox__status  RENAME TO idx_onno_outbox__status;   -- on onno_outbox
ALTER INDEX IF EXISTS onec_comments_target_idx RENAME TO onno_comments_target_idx;  -- on onno_comments
ALTER INDEX IF EXISTS onec_mail_outbox_idem    RENAME TO onno_mail_outbox_idem;     -- unique, on onno_mail_outbox

-- --- Not data, so NOT migrated ----------------------------------------------
-- * onno_cluster_events — a Postgres LISTEN/NOTIFY channel (ephemeral), not a
--   table. Just don't run mixed old/new nodes: the default channel name changed
--   from onec_cluster_events to onno_cluster_events, so a half-upgraded cluster
--   would split-brain its event bus until every node is on the onno release.
--
-- --- Cosmetic, safe to ignore -----------------------------------------------
-- * Auto-generated PRIMARY KEY / UNIQUE constraint names (e.g.
--   onec_schema_history_pkey) keep their onec_ prefix after a table rename.
--   The framework never references constraints by name, so this is harmless;
--   renaming them is optional and dialect-specific.
