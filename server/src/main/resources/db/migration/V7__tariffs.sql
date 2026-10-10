-- Tariffs the owner builds in the admin panel. Never deleted: archived ones stay for history.
CREATE TABLE tariffs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL,
  duration_value int NOT NULL CHECK (duration_value > 0),
  duration_unit text NOT NULL CHECK (duration_unit IN ('days','months')),
  price_rub_minor bigint NOT NULL CHECK (price_rub_minor > 0),
  price_eur_minor bigint NOT NULL CHECK (price_eur_minor > 0),
  device_limit int NOT NULL CHECK (device_limit > 0),
  -- Per calendar month; NULL = unlimited.
  traffic_gb bigint CHECK (traffic_gb > 0),
  badge text,
  sort int NOT NULL DEFAULT 0,
  status text NOT NULL DEFAULT 'active' CHECK (status IN ('active','hidden','archived')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- An order keeps the tariff's terms as they were when it was placed; the subscription
-- copies them, so later edits of the tariff never change what someone already bought.
ALTER TABLE orders ADD COLUMN tariff_id uuid REFERENCES tariffs(id), ADD COLUMN terms jsonb;
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_channel_check;
ALTER TABLE orders ADD CONSTRAINT orders_channel_check CHECK (channel IN ('web','telegram','app'));

ALTER TABLE subscriptions
  ADD COLUMN tariff_id uuid REFERENCES tariffs(id),
  ADD COLUMN tariff_name text,
  ADD COLUMN device_limit int,
  ADD COLUMN monthly_bytes bigint;
ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_source_check;
ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_source_check
  CHECK (source IN ('apple','google','referral','dev','web','telegram','app'));
