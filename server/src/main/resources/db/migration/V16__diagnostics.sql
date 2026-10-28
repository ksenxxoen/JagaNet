-- Problem reports the Boop apps send ("Сообщить о проблеме", POST /v1/diagnostics), issue #11.
-- Kept 30 days. The text has keys, tokens, configs and key links cut out by the app.
-- A removed device keeps its reports until they age out; a removed account takes them along.
CREATE TABLE diagnostics (
  id bigserial PRIMARY KEY,
  user_id uuid REFERENCES users(id) ON DELETE CASCADE,
  device_id uuid REFERENCES devices(id) ON DELETE SET NULL,
  app_version text,
  platform text,
  os_version text,
  state text,
  problem text,
  server_id text,
  country text,
  host text,
  resolved text, -- the addresses the host resolved to, space-separated
  handshake_age_sec bigint,
  rx_bytes bigint,
  tx_bytes bigint,
  probe text,
  comment text,
  body text NOT NULL,
  received_at timestamptz NOT NULL
);
CREATE INDEX diagnostics_received ON diagnostics(received_at);
CREATE INDEX diagnostics_device ON diagnostics(device_id, received_at);
CREATE INDEX diagnostics_user ON diagnostics(user_id, received_at);
