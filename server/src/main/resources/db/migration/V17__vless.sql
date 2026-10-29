-- VLESS+Reality nodes (issue #28). Their settings live in servers.protocols->'vless' (jsonb, no
-- schema change); the apps' server list now also shows the country's name in Russian, from the
-- ISO code, unless the owner sets their own here.
-- Additive and nullable: the running panel ignores the column, so it is safe to apply first.
-- Manual rollback (Flyway community has no down migrations):
--   ALTER TABLE servers DROP COLUMN IF EXISTS country_name;
--   DELETE FROM flyway_schema_history WHERE version = '17';
ALTER TABLE servers ADD COLUMN IF NOT EXISTS country_name text
  CHECK (country_name IS NULL OR char_length(country_name) BETWEEN 1 AND 60);
