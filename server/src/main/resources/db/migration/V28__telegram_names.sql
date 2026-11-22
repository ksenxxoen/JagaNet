-- Telegram first and last name from updates and login, for the admin list and analytics labels.
-- Additive only: new nullable columns the running panel (V27) never reads. Safe to apply before
-- the new panel starts.
-- Manual rollback (Flyway community has no down migrations):
--   ALTER TABLE users DROP COLUMN IF EXISTS tg_first_name, DROP COLUMN IF EXISTS tg_last_name;
--   DELETE FROM flyway_schema_history WHERE version = '28';

ALTER TABLE users ADD COLUMN IF NOT EXISTS tg_first_name text;
ALTER TABLE users ADD COLUMN IF NOT EXISTS tg_last_name text;
