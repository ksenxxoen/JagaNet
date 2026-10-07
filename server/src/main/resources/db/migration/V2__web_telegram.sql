-- Website and Telegram sales: orders, VPN keys for other apps, Telegram accounts.

ALTER TABLE subscriptions DROP CONSTRAINT subscriptions_source_check;
ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_source_check
  CHECK (source IN ('apple','google','referral','dev','web','telegram'));

-- Telegram-only accounts get a placeholder email (tg<id>@telegram.invalid).
ALTER TABLE users ADD COLUMN telegram_id bigint UNIQUE;

-- A VPN config generated on the server (the device row holds its tunnel). Unlike app
-- devices, the server must know the private key to show the config again.
CREATE TABLE access_keys (
  device_id uuid PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  link_token text NOT NULL UNIQUE,
  private_key_enc text NOT NULL,
  -- The TunnelConfig issued for it (endpoint, server key, address, obfuscation).
  config jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX access_keys_user ON access_keys(user_id);

CREATE TABLE orders (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  product_id text NOT NULL,
  channel text NOT NULL CHECK (channel IN ('web','telegram')),
  provider text NOT NULL,
  amount_minor bigint NOT NULL,
  currency text NOT NULL,
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','paid','cancelled')),
  external_id text,
  created_at timestamptz NOT NULL DEFAULT now(),
  paid_at timestamptz
);
CREATE INDEX orders_user ON orders(user_id, created_at);
