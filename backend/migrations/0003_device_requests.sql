-- Migration 0003: supervisor devices approved from Telegram.
-- The app sends a request; the owner accepts/rejects it with inline buttons in the bot.
CREATE TABLE device_requests (
  id              TEXT PRIMARY KEY,
  secret_hash     TEXT NOT NULL,                 -- SHA-256 of the polling secret held only by the requesting app
  name            TEXT NOT NULL,
  device_name     TEXT,
  ip              TEXT,
  status          TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','rejected','claimed','expired')),
  role            TEXT CHECK (role IN ('EDITOR','ADMIN','SUPER_ADMIN')),
  user_id         TEXT REFERENCES users(id) ON DELETE SET NULL,
  decided_by_tg   INTEGER,
  decided_at      INTEGER,
  created_at      INTEGER NOT NULL,
  expires_at      INTEGER NOT NULL
);
CREATE INDEX ix_device_requests_status ON device_requests(status, created_at);
