-- Telegram bot with the full service (issue #38): linking Telegram to an e-mail account,
-- notifications before the subscription ends, support chat, who invited a Telegram user.
-- Additive only: new nullable / defaulted columns and new tables. The running panel (V18) never
-- reads them, so this is safe to apply before the new panel starts.
-- Manual rollback (Flyway community has no down migrations):
--   DROP TABLE IF EXISTS telegram_support_messages;
--   DROP TABLE IF EXISTS telegram_notices;
--   DROP TABLE IF EXISTS telegram_link_codes;
--   ALTER TABLE users DROP COLUMN IF EXISTS tg_username, DROP COLUMN IF EXISTS tg_notify,
--     DROP COLUMN IF EXISTS tg_start_ref, DROP COLUMN IF EXISTS tg_blocked_at;
--   DELETE FROM flyway_schema_history WHERE version = '19';

-- users.telegram_id (V2, UNIQUE) stays the one link between a Telegram user and an account.
ALTER TABLE users ADD COLUMN IF NOT EXISTS tg_username text;
-- Reminders before / at the end of the subscription and payment messages; the user turns them off in the bot.
ALTER TABLE users ADD COLUMN IF NOT EXISTS tg_notify boolean NOT NULL DEFAULT true;
-- The raw code of t.me/<bot>?start=ref_<code> the account was made with, even when it matched
-- no referral link (groundwork for the referral program, issue #40). referred_by is set as before.
ALTER TABLE users ADD COLUMN IF NOT EXISTS tg_start_ref text;
-- Telegram said the user blocked the bot: no more reminders until they write again.
ALTER TABLE users ADD COLUMN IF NOT EXISTS tg_blocked_at timestamptz;

-- One-time codes from the website's account page: /start link_<code> binds that Telegram to the account.
CREATE TABLE IF NOT EXISTS telegram_link_codes (
  code_hash text PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL,
  used_at timestamptz
);

-- Reminders already sent, one per kind and end date: a renewal (new end date) starts over.
CREATE TABLE IF NOT EXISTS telegram_notices (
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind text NOT NULL CHECK (kind IN ('3d', '1d', 'ended')),
  expires_at timestamptz NOT NULL,
  sent_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, kind, expires_at)
);

-- A user's message forwarded to the support chat: the admin's reply to it goes back to the user.
CREATE TABLE IF NOT EXISTS telegram_support_messages (
  admin_chat bigint NOT NULL,
  admin_message_id bigint NOT NULL,
  user_id uuid REFERENCES users(id) ON DELETE CASCADE,
  user_chat bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (admin_chat, admin_message_id)
);
