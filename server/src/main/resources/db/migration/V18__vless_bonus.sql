-- One VLESS key per user, outside the plan's device limit (issue #33).
-- The key is a system device (devices.kind = 'vless_bonus'): the device limit counts only
-- kind = 'device', and users can't delete it. Its UUID and link token live in vless_keys, so
-- the key keeps the same UUID while its tunnel is off (access ended) and when it moves nodes.
-- Additive only: the running panel (V17) never reads the new column and inserts devices with
-- the default kind, so this is safe to apply before the new panel starts.
-- Manual rollback (Flyway community has no down migrations):
--   DELETE FROM tunnels WHERE device_id IN (SELECT id FROM devices WHERE kind = 'vless_bonus');
--   DELETE FROM devices WHERE kind = 'vless_bonus';
--   DROP TABLE IF EXISTS vless_keys;
--   ALTER TABLE devices DROP COLUMN IF EXISTS kind;
--   DELETE FROM flyway_schema_history WHERE version = '18';
ALTER TABLE devices ADD COLUMN IF NOT EXISTS kind text NOT NULL DEFAULT 'device'
  CHECK (kind IN ('device', 'vless_bonus'));

CREATE TABLE IF NOT EXISTS vless_keys (
  user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  device_id uuid NOT NULL UNIQUE REFERENCES devices(id) ON DELETE CASCADE,
  uuid text NOT NULL UNIQUE,
  link_token text NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now()
);
