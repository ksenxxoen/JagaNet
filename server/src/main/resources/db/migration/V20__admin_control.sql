-- Admin control (issue #39): blocking users, where devices connect from, and a log of admin actions.
-- Additive only: new nullable columns and new tables. The running panel (V18/V19) never reads them,
-- so this is safe to apply before the new panel starts. The admin's note on a user is users.note
-- (V10), which test accounts already use for "who it is for".
-- Manual rollback (Flyway community has no down migrations):
--   DROP TABLE IF EXISTS admin_audit;
--   DROP TABLE IF EXISTS client_ips;
--   ALTER TABLE node_peer_counters DROP COLUMN IF EXISTS endpoint, DROP COLUMN IF EXISTS ips;
--   ALTER TABLE tunnels DROP COLUMN IF EXISTS last_ip, DROP COLUMN IF EXISTS last_ip_at;
--   ALTER TABLE users DROP COLUMN IF EXISTS blocked_at, DROP COLUMN IF EXISTS blocked_reason;
--   DELETE FROM flyway_schema_history WHERE version = '20';

-- A blocked user can't use the API and their peers are taken off every node (keys are kept).
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS blocked_at timestamptz,
  ADD COLUMN IF NOT EXISTS blocked_reason text;

-- The latest client address a node saw for the tunnel (AmneziaWG peer endpoint, Xray online IP).
ALTER TABLE tunnels
  ADD COLUMN IF NOT EXISTS last_ip text,
  ADD COLUMN IF NOT EXISTS last_ip_at timestamptz;

-- What the agent reported with the counters: the peer's endpoint (AmneziaWG / WireGuard) and the
-- user's online IPs with their last-seen time (VLESS, {"ip": epochSeconds}). Older agents send neither.
ALTER TABLE node_peer_counters
  ADD COLUMN IF NOT EXISTS endpoint text,
  ADD COLUMN IF NOT EXISTS ips jsonb;

-- Client addresses per device, for "how many places use this subscription" (unique IPs in 24 hours).
-- Kept 7 days (hourly cleanup). Only the address and when it was seen: no destinations.
CREATE TABLE IF NOT EXISTS client_ips (
  device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  ip text NOT NULL,
  server_id text,
  first_seen timestamptz NOT NULL,
  last_seen timestamptz NOT NULL,
  PRIMARY KEY (device_id, ip)
);
CREATE INDEX IF NOT EXISTS client_ips_last_seen ON client_ips(last_seen);
CREATE INDEX IF NOT EXISTS client_ips_ip ON client_ips(ip);

-- Every admin action on a user: who did it, when, what (details hold old and new values).
CREATE TABLE IF NOT EXISTS admin_audit (
  id bigserial PRIMARY KEY,
  admin_id uuid REFERENCES users(id) ON DELETE SET NULL,
  admin_email text NOT NULL,
  user_id uuid REFERENCES users(id) ON DELETE SET NULL,
  user_email text,
  action text NOT NULL,
  details jsonb NOT NULL DEFAULT '{}',
  at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS admin_audit_user ON admin_audit(user_id, at);
CREATE INDEX IF NOT EXISTS admin_audit_at ON admin_audit(at);
