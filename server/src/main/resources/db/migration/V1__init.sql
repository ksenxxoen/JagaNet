-- Append-only: never edit a migration that has shipped; add V2__… instead.
CREATE TABLE users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email text NOT NULL UNIQUE,
  role text NOT NULL DEFAULT 'user' CHECK (role IN ('user','owner')),
  referral_code text NOT NULL UNIQUE,
  referred_by uuid REFERENCES users(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE otp_codes (
  email text PRIMARY KEY,
  code_hash text NOT NULL,
  referral_code text,
  attempts int NOT NULL DEFAULT 0,
  expires_at timestamptz NOT NULL
);

CREATE TABLE devices (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name text NOT NULL,
  platform text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_seen_at timestamptz,
  removed_at timestamptz
);
CREATE INDEX devices_user ON devices(user_id) WHERE removed_at IS NULL;

CREATE TABLE sessions (
  token_hash text PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_used_at timestamptz NOT NULL DEFAULT now(),
  revoked_at timestamptz
);

CREATE TABLE pairing_codes (
  code_hash text PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL,
  used_at timestamptz
);

-- A VPN node. "protocols" maps protocol id -> that protocol's node settings,
-- e.g. {"wireguard":{"endpoint":"vpn1.example.com:51820","publicKey":"…","interface":"wg0"}}.
CREATE TABLE servers (
  id text PRIMARY KEY,
  name text NOT NULL,
  city text NOT NULL,
  country_code char(2) NOT NULL,
  subnet text NOT NULL,
  dns text[] NOT NULL DEFAULT '{1.1.1.1}',
  mtu int NOT NULL DEFAULT 1280,
  protocols jsonb NOT NULL DEFAULT '{}',
  max_peers int NOT NULL DEFAULT 250,
  monthly_traffic_limit_bytes bigint,
  paid_through date,
  active boolean NOT NULL DEFAULT true
);

-- At most one tunnel per device. Protocol-specific data lives in jsonb so the
-- schema never changes when a protocol is added.
CREATE TABLE tunnels (
  device_id uuid PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
  server_id text NOT NULL REFERENCES servers(id),
  protocol text NOT NULL,
  address text NOT NULL,
  peer_key text NOT NULL,
  client_params jsonb NOT NULL DEFAULT '{}',
  suspended_reason text,
  last_rx bigint NOT NULL DEFAULT 0,
  last_tx bigint NOT NULL DEFAULT 0,
  last_seen_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (server_id, address),
  UNIQUE (protocol, peer_key)
);

CREATE TABLE subscriptions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  product_id text NOT NULL,
  source text NOT NULL CHECK (source IN ('apple','google','referral','dev')),
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','expired','cancelled','refunded')),
  is_renewal boolean NOT NULL DEFAULT false,
  auto_renew boolean NOT NULL DEFAULT true,
  external_id text,
  started_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  cancelled_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (source, external_id)
);
CREATE INDEX subscriptions_user ON subscriptions(user_id, expires_at);

-- Hourly traffic per device. Only byte counts: no destinations, no IPs.
CREATE TABLE traffic_samples (
  device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  bucket timestamptz NOT NULL,
  rx_bytes bigint NOT NULL DEFAULT 0,
  tx_bytes bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (device_id, bucket)
);

CREATE TABLE connection_sessions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  protocol text NOT NULL,
  started_at timestamptz NOT NULL,
  ended_at timestamptz,
  peak_down_bps bigint
);
CREATE INDEX connection_sessions_device ON connection_sessions(device_id, started_at);
