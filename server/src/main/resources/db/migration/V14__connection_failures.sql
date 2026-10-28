-- Failed connection attempts the Boop apps report (POST /v1/tunnel/events: handshake_failed, no_rx).
-- Kept 30 days. No user addresses: endpoint is the node's address the app was knocking on.
CREATE TABLE connection_failures (
  id bigserial PRIMARY KEY,
  device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  user_id uuid REFERENCES users(id) ON DELETE CASCADE,
  server_id text,
  type text NOT NULL,
  reason text,
  endpoint text,
  network text,
  app_version text,
  platform text,
  -- When it happened on the device (an app may send it later), and when the panel got it.
  at timestamptz NOT NULL,
  received_at timestamptz NOT NULL
);
CREATE INDEX connection_failures_at ON connection_failures(at);
CREATE INDEX connection_failures_server ON connection_failures(server_id, at);
CREATE INDEX connection_failures_device ON connection_failures(device_id, received_at);
