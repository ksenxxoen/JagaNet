-- Messages from the website's support form, kept so none is lost when e-mail and Telegram both fail.
-- Additive only: a new table the panel before this migration never reads.
--
-- Manual rollback (Flyway community has no down migrations):
--   DROP TABLE IF EXISTS support_messages;
--   DELETE FROM flyway_schema_history WHERE version = '23';
CREATE TABLE IF NOT EXISTS support_messages (
  id bigserial PRIMARY KEY,
  created_at timestamptz NOT NULL,
  name text,
  email text NOT NULL,
  subject text NOT NULL,
  message text NOT NULL,
  user_id uuid REFERENCES users(id) ON DELETE SET NULL,
  ip text,
  emailed boolean NOT NULL DEFAULT false,
  telegram boolean NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS support_messages_undelivered ON support_messages (created_at) WHERE NOT emailed AND NOT telegram;
