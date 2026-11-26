-- Platega payments (issue #154). Additive only: existing orders keep working as before.
-- V29: the last migration on the base branch is V28, so Flyway sees no gap.

-- The payment link the provider gave when the order was made (Platega's "redirect"),
-- and the provider's own status of the payment (CONFIRMED, CANCELED, CHARGEBACKED, PARTIALLY_REFUNDED, DISPUTED…).
ALTER TABLE orders
  ADD COLUMN checkout_url text,
  ADD COLUMN provider_status text,
  ADD COLUMN provider_status_at timestamptz;

-- One order per Platega transaction id: a callback finds its order by the id Platega gave.
CREATE UNIQUE INDEX orders_platega_tx ON orders(external_id) WHERE provider = 'platega' AND external_id IS NOT NULL;

-- Every callback (and status check) received, after the sender was verified: what Platega said and what we did.
-- A repeated delivery of the same (transaction, status) is stored once; the order is only ever paid once (orders.status).
CREATE TABLE payment_events (
  id bigserial PRIMARY KEY,
  provider text NOT NULL,
  external_id text NOT NULL,
  order_id uuid REFERENCES orders(id) ON DELETE SET NULL,
  status text NOT NULL,
  amount_minor bigint,
  currency text,
  -- paid, already_paid, cancelled, mismatch, recorded, unknown_order
  outcome text NOT NULL,
  -- The body as received (no secrets: those come in headers).
  body jsonb,
  received_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX payment_events_once ON payment_events(provider, external_id, status, outcome);
CREATE INDEX payment_events_order ON payment_events(order_id, received_at);
