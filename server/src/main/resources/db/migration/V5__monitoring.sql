-- Server monitoring: one row of measurements per minute (kept 30 days) and alerts.

CREATE TABLE server_metrics (
  ts timestamptz PRIMARY KEY,
  cpu real NOT NULL,
  mem_used bigint NOT NULL,
  mem_total bigint NOT NULL,
  disk_free bigint NOT NULL,
  disk_total bigint NOT NULL,
  -- Internet interface: speed during the interval (bits/s) and bytes moved in it.
  rx_bps bigint NOT NULL DEFAULT 0,
  tx_bps bigint NOT NULL DEFAULT 0,
  rx_bytes bigint NOT NULL DEFAULT 0,
  tx_bytes bigint NOT NULL DEFAULT 0,
  utilization real,
  ping_ms real,
  loss_pct real,
  peers int NOT NULL DEFAULT 0,
  online int NOT NULL DEFAULT 0,
  errors bigint NOT NULL DEFAULT 0,
  drops bigint NOT NULL DEFAULT 0
);

CREATE TABLE monitor_alerts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  key text NOT NULL,
  level text NOT NULL CHECK (level IN ('warning','critical')),
  message text NOT NULL,
  args jsonb NOT NULL DEFAULT '{}',
  opened_at timestamptz NOT NULL,
  resolved_at timestamptz,
  last_notified_at timestamptz
);
-- At most one open alert per check.
CREATE UNIQUE INDEX monitor_alerts_open ON monitor_alerts(key) WHERE resolved_at IS NULL;
CREATE INDEX monitor_alerts_time ON monitor_alerts(opened_at);
