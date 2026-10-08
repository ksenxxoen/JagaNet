-- Interface language chosen in the Telegram bot ("ru", "de", "en"); null = default (Russian).
ALTER TABLE users ADD COLUMN lang text;
