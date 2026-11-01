-- Server status page: one health sample per node agent report (about one a minute), for the per-node
-- sparklines and charts. The main server's own measurements stay in server_metrics.
-- Additive only: a new table the running panel never reads, so it is safe to apply before the new panel starts.
-- Kept 30 days (hourly cleanup in the panel). About 1440 rows per node a day.
-- Manual rollback (Flyway community has no down migrations):
--   DROP TABLE IF EXISTS node_metrics;
--   DELETE FROM flyway_schema_history WHERE version = '24';
CREATE TABLE IF NOT EXISTS node_metrics (
  server_id text NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
  ts timestamptz NOT NULL,
  -- 0..1
  cpu double precision NOT NULL DEFAULT 0,
  mem_used bigint NOT NULL DEFAULT 0,
  mem_total bigint NOT NULL DEFAULT 0,
  -- the internet interface, bits per second
  rx_bps bigint NOT NULL DEFAULT 0,
  tx_bps bigint NOT NULL DEFAULT 0,
  -- devices seen in the last 3 minutes
  online integer NOT NULL DEFAULT 0,
  PRIMARY KEY (server_id, ts)
);
CREATE INDEX IF NOT EXISTS node_metrics_ts ON node_metrics(ts);
