-- Traffic analytics: which server carried a device's traffic, and how much of it was BitTorrent.
-- Still only byte counts: no destinations, no addresses.
ALTER TABLE traffic_samples
  ADD COLUMN server_id text,
  ADD COLUMN torrent_rx_bytes bigint NOT NULL DEFAULT 0,
  ADD COLUMN torrent_tx_bytes bigint NOT NULL DEFAULT 0;
UPDATE traffic_samples s SET server_id = t.server_id FROM tunnels t WHERE t.device_id = s.device_id;
CREATE INDEX traffic_samples_bucket ON traffic_samples(bucket);
CREATE INDEX traffic_samples_server ON traffic_samples(server_id, bucket);

ALTER TABLE tunnels
  ADD COLUMN last_torrent_rx bigint NOT NULL DEFAULT 0,
  ADD COLUMN last_torrent_tx bigint NOT NULL DEFAULT 0;

ALTER TABLE node_peer_counters
  ADD COLUMN torrent_rx bigint NOT NULL DEFAULT 0,
  ADD COLUMN torrent_tx bigint NOT NULL DEFAULT 0;
