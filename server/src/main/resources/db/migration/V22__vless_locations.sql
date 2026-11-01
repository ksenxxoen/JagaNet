-- Several VLESS locations in one key and the server-side balancer (issue #41).
--
-- servers: how a VLESS node shows up in the subscription and the apps (display_name, sort_order,
-- in_subscription) and its part in a cascade (cascade_role: an entry balances its users over the
-- exits; cascade_uuid: the entry's own Xray user on the exits, not a secret of either node).
-- vless_replicas: the same user UUID on every other active VLESS node, next to the tunnel's own
-- node; dropped with the tunnel (ON DELETE CASCADE), so revoking a key revokes it everywhere.
--
-- Additive only, nothing is rewritten: the panel before this migration never reads the new
-- columns or the table, so it is safe to apply before the new panel starts.
-- V19 to V21 are reserved for parallel work (#38, #39, #40): apply this one after them. When all
-- of them are merged and deployed together Flyway applies them in order; only a deploy with
-- V22 before any of V19 to V21 would need flyway outOfOrder.
--
-- Manual rollback (Flyway community has no down migrations):
--   DROP TABLE IF EXISTS vless_replicas;
--   ALTER TABLE servers DROP COLUMN IF EXISTS display_name, DROP COLUMN IF EXISTS sort_order,
--     DROP COLUMN IF EXISTS in_subscription, DROP COLUMN IF EXISTS cascade_role, DROP COLUMN IF EXISTS cascade_uuid;
--   DELETE FROM flyway_schema_history WHERE version = '22';
ALTER TABLE servers ADD COLUMN IF NOT EXISTS display_name text
  CHECK (display_name IS NULL OR char_length(display_name) BETWEEN 1 AND 60);
ALTER TABLE servers ADD COLUMN IF NOT EXISTS sort_order int NOT NULL DEFAULT 0;
ALTER TABLE servers ADD COLUMN IF NOT EXISTS in_subscription boolean NOT NULL DEFAULT true;
ALTER TABLE servers ADD COLUMN IF NOT EXISTS cascade_role text
  CHECK (cascade_role IS NULL OR cascade_role IN ('entry', 'exit'));
ALTER TABLE servers ADD COLUMN IF NOT EXISTS cascade_uuid text UNIQUE;

CREATE TABLE IF NOT EXISTS vless_replicas (
  device_id uuid NOT NULL REFERENCES tunnels(device_id) ON DELETE CASCADE,
  server_id text NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
  peer_key text NOT NULL,
  last_rx bigint NOT NULL DEFAULT 0,
  last_tx bigint NOT NULL DEFAULT 0,
  last_torrent_rx bigint NOT NULL DEFAULT 0,
  last_torrent_tx bigint NOT NULL DEFAULT 0,
  last_seen_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (device_id, server_id)
);
CREATE INDEX IF NOT EXISTS vless_replicas_server ON vless_replicas (server_id, peer_key);
