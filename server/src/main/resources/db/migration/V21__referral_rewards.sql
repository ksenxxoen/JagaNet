-- "Invite a friend, get +14 days" (issue #40): rewards for inviting, anti-fraud fingerprints,
-- and the rule that an account's inviter never changes.
-- Additive only: two new tables, a NOT VALID check (no table scan) and a trigger that only blocks
-- changing a non-empty users.referred_by, which no code does. The running panel (V19/V20) never
-- reads the new tables, so this is safe to apply before the new panel starts.
-- Manual rollback (Flyway community has no down migrations):
--   DROP TRIGGER IF EXISTS users_referrer_fixed ON users;
--   DROP FUNCTION IF EXISTS referral_keep_referrer();
--   ALTER TABLE users DROP CONSTRAINT IF EXISTS users_not_self_referred;
--   DROP TABLE IF EXISTS referral_rewards;
--   DROP TABLE IF EXISTS user_fingerprints;
--   DELETE FROM settings WHERE key = 'referral_rewards';
--   DELETE FROM flyway_schema_history WHERE version = '21';
-- Reward days already given stay as subscriptions rows with source 'referral' (external_id 'reward:<id>').

-- One row per reward: for the inviter (kind 'referrer') and, when turned on, for the invited
-- person (kind 'invitee'). UNIQUE (invitee_id, kind): an invited person counts once, ever, so a
-- repeated webhook or a second payment can't reward again.
CREATE TABLE IF NOT EXISTS referral_rewards (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  -- Who gets the days: referrer_id for 'referrer', invitee_id for 'invitee'.
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  referrer_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  invitee_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind text NOT NULL CHECK (kind IN ('referrer', 'invitee')),
  -- What earned it: the invited person's first payment, or their sign-up.
  reason text NOT NULL CHECK (reason IN ('first_payment', 'signup')),
  order_id uuid REFERENCES orders(id) ON DELETE SET NULL,
  days int NOT NULL CHECK (days >= 0),
  -- granted: days added; capped: over the monthly limit, nothing added; cancelled: taken back by an admin.
  status text NOT NULL CHECK (status IN ('granted', 'capped', 'cancelled')),
  subscription_id uuid REFERENCES subscriptions(id) ON DELETE SET NULL,
  -- Anti-fraud: the invited person signed in from the inviter's network ('same_ip') or browser ('same_device').
  suspicion text CHECK (suspicion IN ('same_ip', 'same_device')),
  created_at timestamptz NOT NULL DEFAULT now(),
  -- 'telegram', 'email' or 'none' once the recipient was told (or there was no way to).
  notified_via text,
  notified_at timestamptz,
  cancelled_at timestamptz,
  CHECK (referrer_id <> invitee_id),
  UNIQUE (invitee_id, kind)
);
CREATE INDEX IF NOT EXISTS referral_rewards_user ON referral_rewards(user_id, created_at);
CREATE INDEX IF NOT EXISTS referral_rewards_pending ON referral_rewards(created_at) WHERE notified_at IS NULL;

-- Where an account signed in from, for the anti-fraud flag only. No IP addresses: HMACs (with
-- AUTH_SECRET) of the IP ('ip') and of IP + browser ('device'). Kept 90 days.
CREATE TABLE IF NOT EXISTS user_fingerprints (
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind text NOT NULL CHECK (kind IN ('ip', 'device')),
  hash text NOT NULL,
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, kind, hash)
);
CREATE INDEX IF NOT EXISTS user_fingerprints_hash ON user_fingerprints(hash);

-- Nobody invites themselves. NOT VALID: checked for new and changed rows, existing ones are not scanned.
ALTER TABLE users ADD CONSTRAINT users_not_self_referred CHECK (referred_by IS NULL OR referred_by <> id) NOT VALID;

-- The inviter is set when the account is made and never changes afterwards. Clearing it
-- (the inviter's account deleted: ON DELETE SET NULL) is still allowed.
CREATE OR REPLACE FUNCTION referral_keep_referrer() RETURNS trigger AS $$
BEGIN
  IF NEW.referred_by IS NOT NULL AND NEW.referred_by IS DISTINCT FROM OLD.referred_by THEN
    RAISE EXCEPTION 'users.referred_by can not be changed after sign-up';
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS users_referrer_fixed ON users;
CREATE TRIGGER users_referrer_fixed BEFORE UPDATE OF referred_by ON users FOR EACH ROW EXECUTE FUNCTION referral_keep_referrer();
