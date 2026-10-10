-- Remote VPN nodes. The main server keeps the list of peers (tunnels); an agent on each
-- node pulls it, applies it to the node's interface and reports counters and health.
-- agent_token_hash NULL = the node runs on this machine (local driver).
ALTER TABLE servers
  ADD COLUMN agent_token_hash text UNIQUE,
  -- The node's identity lives here, not on the machine: its private key (encrypted) and an
  -- optional host name for the endpoint. A replacement machine takes the same identity, so
  -- devices and key files keep working without any change on their side.
  ADD COLUMN node_key_enc text,
  ADD COLUMN endpoint_host text,
  ADD COLUMN registered_at timestamptz,
  ADD COLUMN last_report_at timestamptz,
  ADD COLUMN report jsonb,
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();

-- Latest cumulative counters a node reported, per peer.
CREATE TABLE node_peer_counters (
  server_id text NOT NULL REFERENCES servers(id) ON DELETE CASCADE,
  protocol text NOT NULL,
  peer_key text NOT NULL,
  rx_bytes bigint NOT NULL,
  tx_bytes bigint NOT NULL,
  last_handshake timestamptz,
  PRIMARY KEY (server_id, protocol, peer_key)
);
