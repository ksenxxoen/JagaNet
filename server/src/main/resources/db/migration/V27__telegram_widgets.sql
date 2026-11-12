-- Telegram status card (issue #140): the last status widget (Bot API 10.4 sendWidget) sent to each
-- user, so the bot can update it in place with editMessageWidgetData.
-- Additive only: a new table the running panel (V26) never reads. Safe to apply before the new panel starts.
-- Manual rollback (Flyway community has no down migrations):
--   DROP TABLE IF EXISTS telegram_widgets;
--   DELETE FROM flyway_schema_history WHERE version = '27';

CREATE TABLE IF NOT EXISTS telegram_widgets (
  user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  chat_id bigint NOT NULL,
  message_id bigint NOT NULL,
  -- "r" in the card's data: grows with every update, the card ignores older data.
  revision bigint NOT NULL,
  -- What the card shows now: active, none or ended, and the end date (to update it when the subscription ends).
  status text NOT NULL,
  expires_at timestamptz,
  sent_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS telegram_widgets_expiring ON telegram_widgets (expires_at) WHERE status = 'active';
