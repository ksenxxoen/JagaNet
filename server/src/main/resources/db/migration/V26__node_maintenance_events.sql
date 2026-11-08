-- Issue #115, server status page: a "maintenance / expected to be silent" flag per node and a log of
-- node events (went silent, came back, restart, maintenance on/off) for the node card.
-- Additive only: a new column with a default and a new table the running panel never reads.
-- Manual rollback (Flyway community has no down migrations):
--   DROP TABLE IF EXISTS node_events;
--   ALTER TABLE servers DROP COLUMN IF EXISTS maintenance;
--   DELETE FROM flyway_schema_history WHERE version = '26';
ALTER TABLE servers ADD COLUMN IF NOT EXISTS maintenance boolean NOT NULL DEFAULT false;
CREATE TABLE IF NOT EXISTS node_events (
  id bigserial PRIMARY KEY,
  server_id text NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
  ts timestamptz NOT NULL,
  -- offline, online, restart, maintenance_on, maintenance_off
  kind text NOT NULL,
  -- plain facts as a JSON object, e.g. {"downMinutes":"12"}
  args jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX IF NOT EXISTS node_events_server_ts ON node_events(server_id, ts DESC);
