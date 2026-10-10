-- The latest automatic install of a node over SSH (state, step, output). SSH passwords are never stored.
ALTER TABLE servers ADD COLUMN install jsonb;
