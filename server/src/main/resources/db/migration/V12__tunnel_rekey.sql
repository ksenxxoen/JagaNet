-- The owner asked a device's Boop app for a new key pair; cleared when the app provisions again.
ALTER TABLE tunnels ADD COLUMN rekey boolean NOT NULL DEFAULT false;
