-- Migration 0002: one-time activation codes for the supervisors' app.
-- A supervisor types a single-use code once; the device then keeps a long-lived, revocable session.

ALTER TABLE auth_sessions ADD COLUMN long_lived INTEGER NOT NULL DEFAULT 0;
ALTER TABLE auth_sessions ADD COLUMN device_name TEXT;
ALTER TABLE auth_sessions ADD COLUMN last_used_at INTEGER;

-- Staff accounts created for the app have no usable password (password_hash = '!').
ALTER TABLE users ADD COLUMN is_staff_only INTEGER NOT NULL DEFAULT 0;

CREATE TABLE activation_codes (
  id           TEXT PRIMARY KEY,
  user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  code_hash    TEXT NOT NULL UNIQUE,          -- SHA-256 of the code; the code itself is never stored
  created_by   TEXT REFERENCES users(id) ON DELETE SET NULL,
  created_at   INTEGER NOT NULL,
  expires_at   INTEGER NOT NULL,
  used_at      INTEGER,
  session_id   TEXT
);
CREATE INDEX ix_activation_user ON activation_codes(user_id);
