-- Signing an app in from the website: the app asks, a signed-in browser allows, the app picks up its session.
CREATE TABLE app_logins (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  secret_hash text NOT NULL,
  device_name text NOT NULL,
  platform text NOT NULL,
  match_code text NOT NULL,
  user_id uuid REFERENCES users(id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL
);
