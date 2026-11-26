-- Concurrent IP limit per key: a nullable override on the device (null = the plan's device
-- limit, 0 = no cap), a log of IPs refused while the cap was full, and IPs the admin kicked.
-- Additive only. The running panel (V28) never reads these, so this is safe to apply before
-- the new panel starts.
-- Manual rollback (Flyway community has no down migrations):
--   ALTER TABLE devices DROP COLUMN IF EXISTS ip_limit;
--   DROP TABLE IF EXISTS ip_limit_kicks;
--   DROP TABLE IF EXISTS ip_limit_rejects;
--   DELETE FROM flyway_schema_history WHERE version = '29';

-- Override for this key: null = use the plan, 0 = no cap, 1..100 = that many concurrent IPs.
ALTER TABLE devices ADD COLUMN IF NOT EXISTS ip_limit integer;

-- IPs that tried to connect while the cap was already full (observe and enforce). Kept 7 days.
CREATE TABLE IF NOT EXISTS ip_limit_rejects (
  device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  ip text NOT NULL,
  first_seen timestamptz NOT NULL,
  last_seen timestamptz NOT NULL,
  server_id text,
  PRIMARY KEY (device_id, ip)
);
CREATE INDEX IF NOT EXISTS ip_limit_rejects_last_seen ON ip_limit_rejects(last_seen);

-- IPs the admin reset: they are dropped on the nodes until they go quiet, then the row is removed
-- and the address may come back if a slot is free.
CREATE TABLE IF NOT EXISTS ip_limit_kicks (
  device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  ip text NOT NULL,
  kicked_at timestamptz NOT NULL,
  PRIMARY KEY (device_id, ip)
);
