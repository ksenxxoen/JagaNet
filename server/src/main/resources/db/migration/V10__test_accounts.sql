-- Test accounts made by the owner for testers: a note (who it is for) and a 'test' subscription.
ALTER TABLE users ADD COLUMN note text;
ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_source_check;
ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_source_check
  CHECK (source IN ('apple','google','referral','dev','web','telegram','app','test'));
