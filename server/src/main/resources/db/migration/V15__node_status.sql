-- What the owner says about a node (issue #10): its status for the apps and a short note for
-- the people using it. ok = works; degraded = works with problems (the apps pick it last);
-- blocked = blocked by some networks, maintenance = planned work (the apps don't pick these).
ALTER TABLE servers
  ADD COLUMN status text NOT NULL DEFAULT 'ok' CHECK (status IN ('ok', 'degraded', 'blocked', 'maintenance')),
  ADD COLUMN notice text;
