-- Referral program: several links per user, click tracking, attribution of sign-ups to a link.

CREATE TABLE referral_links (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  -- Upper case; the user's main link uses users.referral_code.
  code text NOT NULL UNIQUE,
  name text NOT NULL,
  main boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  archived_at timestamptz
);
CREATE INDEX referral_links_user ON referral_links(user_id);

-- Which link brought this user (first touch). referred_by stays the link's owner.
ALTER TABLE users ADD COLUMN referral_link_id uuid REFERENCES referral_links(id) ON DELETE SET NULL;
CREATE INDEX users_referral_link ON users(referral_link_id);

-- One row per visit of a link. No IP addresses: "visitor" is an HMAC of IP and browser
-- (or of the Telegram id), only good for counting unique visitors.
CREATE TABLE referral_clicks (
  id bigserial PRIMARY KEY,
  link_id uuid NOT NULL REFERENCES referral_links(id) ON DELETE CASCADE,
  visitor text NOT NULL,
  channel text NOT NULL CHECK (channel IN ('web','telegram')),
  -- Where the visit came from: utm_source, the referring site's host, or null (direct).
  source text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX referral_clicks_link ON referral_clicks(link_id, created_at);

-- Every user gets a main link; existing users too.
INSERT INTO referral_links (user_id, code, name, main, created_at)
SELECT id, upper(referral_code), 'Main link', true, created_at FROM users;

UPDATE users u SET referral_link_id = l.id
  FROM referral_links l WHERE l.user_id = u.referred_by AND l.main;

CREATE FUNCTION referral_on_user_insert() RETURNS trigger AS $$
BEGIN
  -- Referred without a specific link (older code paths): count it for the referrer's main link.
  IF NEW.referred_by IS NOT NULL AND NEW.referral_link_id IS NULL THEN
    SELECT id INTO NEW.referral_link_id FROM referral_links WHERE user_id = NEW.referred_by AND main LIMIT 1;
  END IF;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER users_referral_before BEFORE INSERT ON users FOR EACH ROW EXECUTE FUNCTION referral_on_user_insert();

CREATE FUNCTION referral_main_link() RETURNS trigger AS $$
BEGIN
  INSERT INTO referral_links (user_id, code, name, main) VALUES (NEW.id, upper(NEW.referral_code), 'Main link', true)
    ON CONFLICT (code) DO NOTHING;
  RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER users_referral_after AFTER INSERT ON users FOR EACH ROW EXECUTE FUNCTION referral_main_link();
