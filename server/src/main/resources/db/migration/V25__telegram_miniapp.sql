-- Telegram mini app (issue #87): short-lived sessions, one service device per user for the mini
-- app, and "a new device was connected" notices sent once per device.
-- Additive only: new nullable columns and a new table. The running panel (V24) never reads them,
-- sessions it made keep expires_at NULL (no end, as before) and devices it makes keep service NULL
-- (ordinary devices), so this is safe to apply before the new panel starts.
-- Manual rollback (Flyway community has no down migrations):
--   DROP TABLE IF EXISTS telegram_device_notices;
--   DELETE FROM devices WHERE service IS NOT NULL;   -- their sessions go with them (ON DELETE CASCADE)
--   DROP INDEX IF EXISTS devices_service_one;
--   ALTER TABLE devices DROP COLUMN IF EXISTS service;
--   ALTER TABLE sessions DROP COLUMN IF EXISTS expires_at;
--   DELETE FROM flyway_schema_history WHERE version = '25';

-- NULL = never ends (every session made before this migration, and the apps' and website's own).
-- The mini app's sessions end 12 hours after sign-in.
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS expires_at timestamptz;

-- A service device: not a device of the user. It holds the mini app's sessions, never gets a
-- tunnel, never counts toward the plan's device limit and is never listed as a device.
-- 'tg_miniapp' = the Telegram mini app, one per user (reused on every sign-in).
ALTER TABLE devices ADD COLUMN IF NOT EXISTS service text CHECK (service IN ('tg_miniapp'));
CREATE UNIQUE INDEX IF NOT EXISTS devices_service_one ON devices (user_id, service) WHERE service IS NOT NULL AND removed_at IS NULL;

-- "A new device was connected" messages in Telegram, at most one per device.
CREATE TABLE IF NOT EXISTS telegram_device_notices (
  device_id uuid PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  sent_at timestamptz NOT NULL DEFAULT now()
);
