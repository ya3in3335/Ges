-- Migration 0001: initial schema for the association platform.
-- All timestamps are Unix epoch milliseconds (INTEGER). IDs are random text (UUID v4) unless noted.
PRAGMA foreign_keys = ON;

-- ───────────────────────── Users & sessions ─────────────────────────
CREATE TABLE users (
  id                 TEXT PRIMARY KEY,
  full_name          TEXT NOT NULL,
  email              TEXT NOT NULL COLLATE NOCASE,
  phone_e164         TEXT NOT NULL,
  password_hash      TEXT NOT NULL,
  role               TEXT NOT NULL DEFAULT 'USER'
                     CHECK (role IN ('SUPER_ADMIN','ADMIN','EDITOR','VOLUNTEER','USER')),
  status             TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','suspended','deleted')),
  email_verified_at  INTEGER,
  phone_verified_at  INTEGER,
  phone_verify_method TEXT CHECK (phone_verify_method IN ('telegram')),
  locale             TEXT NOT NULL DEFAULT 'ar' CHECK (locale IN ('ar','fr')),
  -- privacy / notification preferences
  pref_notify_news       INTEGER NOT NULL DEFAULT 1,
  pref_notify_activities INTEGER NOT NULL DEFAULT 1,
  pref_notify_polls      INTEGER NOT NULL DEFAULT 1,
  pref_notify_volunteer  INTEGER NOT NULL DEFAULT 1,
  pref_show_name_public  INTEGER NOT NULL DEFAULT 0,
  failed_logins      INTEGER NOT NULL DEFAULT 0,
  locked_until       INTEGER,
  created_at         INTEGER NOT NULL,
  updated_at         INTEGER NOT NULL
);
CREATE UNIQUE INDEX ux_users_email ON users(email) WHERE status <> 'deleted';
CREATE UNIQUE INDEX ux_users_phone ON users(phone_e164) WHERE status <> 'deleted';
CREATE INDEX ix_users_role ON users(role);
CREATE INDEX ix_users_verified ON users(phone_verified_at);

-- Refresh-token sessions. Only the SHA-256 hash of a refresh token is stored.
CREATE TABLE auth_sessions (
  id               TEXT PRIMARY KEY,
  user_id          TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  refresh_hash     TEXT NOT NULL UNIQUE,
  family_id        TEXT NOT NULL,           -- rotation family, revoked together on reuse
  client           TEXT NOT NULL DEFAULT 'android' CHECK (client IN ('android','admin')),
  created_at       INTEGER NOT NULL,
  expires_at       INTEGER NOT NULL,
  rotated_at       INTEGER,                 -- set when superseded by a newer token
  revoked_at       INTEGER,
  user_agent       TEXT
);
CREATE INDEX ix_sessions_user ON auth_sessions(user_id);
CREATE INDEX ix_sessions_family ON auth_sessions(family_id);

-- One-time codes for password reset / email verification (hash only).
CREATE TABLE auth_codes (
  id          TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  purpose     TEXT NOT NULL CHECK (purpose IN ('password_reset','email_verify')),
  code_hash   TEXT NOT NULL,
  attempts    INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL,
  used_at     INTEGER
);
CREATE INDEX ix_codes_user ON auth_codes(user_id, purpose);

-- ───────────────────────── Telegram verification ─────────────────────────
CREATE TABLE telegram_verifications (
  id                TEXT PRIMARY KEY,
  user_id           TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash        TEXT NOT NULL UNIQUE,     -- SHA-256 of the deep-link token
  status            TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','awaiting_contact','verified','failed','expired','cancelled')),
  telegram_user_id  INTEGER,                  -- bound on /start, never changes afterwards
  telegram_chat_id  INTEGER,
  attempts          INTEGER NOT NULL DEFAULT 0,
  failure_reason    TEXT,
  created_at        INTEGER NOT NULL,
  expires_at        INTEGER NOT NULL,
  consumed_at       INTEGER
);
CREATE INDEX ix_tgv_user ON telegram_verifications(user_id, created_at);
CREATE INDEX ix_tgv_tguser ON telegram_verifications(telegram_user_id, status);

-- A Telegram account can be linked to at most one app account and vice versa.
CREATE TABLE telegram_account_links (
  user_id           TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  telegram_user_id  INTEGER NOT NULL UNIQUE,
  telegram_chat_id  INTEGER NOT NULL,
  verification_id   TEXT NOT NULL UNIQUE REFERENCES telegram_verifications(id),
  linked_at         INTEGER NOT NULL
);

-- Telegram update de-duplication (webhook retries / replays).
CREATE TABLE telegram_updates (
  update_id    INTEGER PRIMARY KEY,
  received_at  INTEGER NOT NULL
);

CREATE TABLE verification_audit (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  verification_id   TEXT,
  user_id           TEXT,
  telegram_user_id  INTEGER,
  event             TEXT NOT NULL,   -- started, bound, contact_ok, phone_mismatch, foreign_contact, expired, ...
  detail            TEXT,            -- never contains full phone numbers or tokens
  created_at        INTEGER NOT NULL
);
CREATE INDEX ix_vaudit_user ON verification_audit(user_id);
CREATE INDEX ix_vaudit_created ON verification_audit(created_at);

-- Outbox for messages to the private Telegram admin channel (retry-safe, de-duplicated).
CREATE TABLE channel_outbox (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  dedupe_key       TEXT NOT NULL UNIQUE,
  text             TEXT NOT NULL,
  status           TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','sent','failed','skipped')),
  attempts         INTEGER NOT NULL DEFAULT 0,
  next_attempt_at  INTEGER NOT NULL,
  last_error       TEXT,
  created_at       INTEGER NOT NULL,
  sent_at          INTEGER
);
CREATE INDEX ix_outbox_due ON channel_outbox(status, next_attempt_at);

-- ───────────────────────── Content ─────────────────────────
CREATE TABLE news (
  id            TEXT PRIMARY KEY,
  title_ar      TEXT NOT NULL,
  title_fr      TEXT,
  body_ar       TEXT NOT NULL,
  body_fr       TEXT,
  category      TEXT NOT NULL DEFAULT 'news' CHECK (category IN ('news','announcement','campaign','report')),
  image_url     TEXT,
  featured      INTEGER NOT NULL DEFAULT 0,
  status        TEXT NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','published','archived')),
  published_at  INTEGER,
  author_id     TEXT REFERENCES users(id) ON DELETE SET NULL,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);
CREATE INDEX ix_news_pub ON news(status, published_at DESC);
CREATE INDEX ix_news_cat ON news(category, status, published_at DESC);

CREATE TABLE activities (
  id              TEXT PRIMARY KEY,
  title_ar        TEXT NOT NULL,
  title_fr        TEXT,
  description_ar  TEXT NOT NULL,
  description_fr  TEXT,
  location        TEXT,
  latitude        REAL,
  longitude       REAL,
  starts_at       INTEGER NOT NULL,
  ends_at         INTEGER NOT NULL,
  capacity        INTEGER,               -- NULL = unlimited
  image_url       TEXT,
  video_url       TEXT,
  is_campaign     INTEGER NOT NULL DEFAULT 0,
  featured        INTEGER NOT NULL DEFAULT 0,
  status          TEXT NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','published','cancelled','archived')),
  registration_open INTEGER NOT NULL DEFAULT 1,
  created_by      TEXT REFERENCES users(id) ON DELETE SET NULL,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL,
  CHECK (ends_at >= starts_at)
);
CREATE INDEX ix_act_time ON activities(status, starts_at);

CREATE TABLE activity_registrations (
  activity_id  TEXT NOT NULL REFERENCES activities(id) ON DELETE CASCADE,
  user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status       TEXT NOT NULL DEFAULT 'registered' CHECK (status IN ('registered','cancelled','attended')),
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL,
  PRIMARY KEY (activity_id, user_id)
);
CREATE INDEX ix_actreg_user ON activity_registrations(user_id);

-- ───────────────────────── Voting ─────────────────────────
CREATE TABLE polls (
  id                  TEXT PRIMARY KEY,
  title_ar            TEXT NOT NULL,
  title_fr            TEXT,
  description_ar      TEXT,
  description_fr      TEXT,
  starts_at           INTEGER NOT NULL,
  ends_at             INTEGER NOT NULL,
  eligibility         TEXT NOT NULL DEFAULT 'verified' CHECK (eligibility IN ('verified','members')),
  result_visibility   TEXT NOT NULL DEFAULT 'after_close' CHECK (result_visibility IN ('live','after_close','admins')),
  anonymous           INTEGER NOT NULL DEFAULT 1,
  status              TEXT NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','published','cancelled')),
  created_by          TEXT REFERENCES users(id) ON DELETE SET NULL,
  created_at          INTEGER NOT NULL,
  updated_at          INTEGER NOT NULL,
  CHECK (ends_at > starts_at)
);
CREATE INDEX ix_polls_time ON polls(status, ends_at);

CREATE TABLE poll_options (
  id          TEXT PRIMARY KEY,
  poll_id     TEXT NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
  label_ar    TEXT NOT NULL,
  label_fr    TEXT,
  description TEXT,
  image_url   TEXT,
  position    INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX ix_popts_poll ON poll_options(poll_id, position);

-- Approved member list for formal elections (eligibility = 'members').
CREATE TABLE poll_eligible_members (
  poll_id   TEXT NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
  user_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  added_by  TEXT,
  added_at  INTEGER NOT NULL,
  PRIMARY KEY (poll_id, user_id)
);

-- Who has voted. The PRIMARY KEY makes a second vote impossible at the database level.
CREATE TABLE poll_participation (
  poll_id    TEXT NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
  user_id    TEXT NOT NULL REFERENCES users(id),
  voted_at   INTEGER NOT NULL,
  receipt    TEXT NOT NULL UNIQUE,      -- random receipt returned to the voter
  PRIMARY KEY (poll_id, user_id)
);

-- Ballots. For anonymous polls user_id is NULL and the random key + WITHOUT ROWID
-- avoid ordering by insertion, so a ballot cannot be linked back to a participation row.
CREATE TABLE votes (
  id         TEXT PRIMARY KEY,
  poll_id    TEXT NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
  option_id  TEXT NOT NULL REFERENCES poll_options(id) ON DELETE CASCADE,
  user_id    TEXT REFERENCES users(id),
  UNIQUE (poll_id, user_id)
) WITHOUT ROWID;
CREATE INDEX ix_votes_poll_opt ON votes(poll_id, option_id);

-- Ballots are immutable once cast.
CREATE TRIGGER trg_votes_no_update BEFORE UPDATE ON votes
BEGIN SELECT RAISE(ABORT, 'ballots are immutable'); END;
CREATE TRIGGER trg_participation_no_update BEFORE UPDATE ON poll_participation
BEGIN SELECT RAISE(ABORT, 'participation is immutable'); END;
-- Options cannot change once a poll has received votes.
CREATE TRIGGER trg_options_locked_update BEFORE UPDATE ON poll_options
WHEN EXISTS (SELECT 1 FROM poll_participation WHERE poll_id = OLD.poll_id)
BEGIN SELECT RAISE(ABORT, 'poll options locked after voting started'); END;
CREATE TRIGGER trg_options_locked_insert BEFORE INSERT ON poll_options
WHEN EXISTS (SELECT 1 FROM poll_participation WHERE poll_id = NEW.poll_id)
BEGIN SELECT RAISE(ABORT, 'poll options locked after voting started'); END;

-- ───────────────────────── Volunteers ─────────────────────────
CREATE TABLE volunteers (
  id              TEXT PRIMARY KEY,
  user_id         TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  full_name       TEXT NOT NULL,
  contact         TEXT NOT NULL,
  skills          TEXT NOT NULL,
  availability    TEXT NOT NULL,
  preferred_areas TEXT,
  experience      TEXT,
  status          TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','rejected')),
  review_note     TEXT,
  reviewed_by     TEXT REFERENCES users(id) ON DELETE SET NULL,
  reviewed_at     INTEGER,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL
);
CREATE UNIQUE INDEX ux_volunteers_user ON volunteers(user_id);
CREATE INDEX ix_volunteers_status ON volunteers(status, created_at);

CREATE TABLE volunteer_assignments (
  volunteer_id  TEXT NOT NULL REFERENCES volunteers(id) ON DELETE CASCADE,
  activity_id   TEXT NOT NULL REFERENCES activities(id) ON DELETE CASCADE,
  role_note     TEXT,
  assigned_by   TEXT,
  assigned_at   INTEGER NOT NULL,
  PRIMARY KEY (volunteer_id, activity_id)
);

-- ───────────────────────── Media ─────────────────────────
CREATE TABLE media (
  id             TEXT PRIMARY KEY,
  kind           TEXT NOT NULL CHECK (kind IN ('photo','video')),
  title_ar       TEXT,
  title_fr       TEXT,
  category       TEXT NOT NULL DEFAULT 'general',
  url            TEXT NOT NULL,          -- R2-served path (/media/...) or external https URL
  thumbnail_url  TEXT,
  r2_key         TEXT UNIQUE,
  content_type   TEXT,
  size_bytes     INTEGER,
  activity_id    TEXT REFERENCES activities(id) ON DELETE SET NULL,
  featured       INTEGER NOT NULL DEFAULT 0,
  status         TEXT NOT NULL DEFAULT 'published' CHECK (status IN ('published','hidden')),
  uploaded_by    TEXT REFERENCES users(id) ON DELETE SET NULL,
  created_at     INTEGER NOT NULL
);
CREATE INDEX ix_media_list ON media(status, kind, created_at DESC);

-- ───────────────────────── Notifications ─────────────────────────
CREATE TABLE notifications (
  id           TEXT PRIMARY KEY,
  topic        TEXT NOT NULL CHECK (topic IN ('news','activities','polls','volunteer','important')),
  title_ar     TEXT NOT NULL,
  title_fr     TEXT,
  body_ar      TEXT NOT NULL,
  body_fr      TEXT,
  target_user_id TEXT REFERENCES users(id) ON DELETE CASCADE,  -- NULL = broadcast to topic
  deep_link    TEXT,
  delivery     TEXT NOT NULL DEFAULT 'queued'
               CHECK (delivery IN ('queued','sent','failed','not_configured')),
  delivery_error TEXT,
  created_by   TEXT REFERENCES users(id) ON DELETE SET NULL,
  created_at   INTEGER NOT NULL,
  sent_at      INTEGER
);
CREATE INDEX ix_notif_created ON notifications(created_at DESC);
CREATE INDEX ix_notif_user ON notifications(target_user_id, created_at DESC);

CREATE TABLE push_devices (
  token       TEXT PRIMARY KEY,
  user_id     TEXT REFERENCES users(id) ON DELETE CASCADE,
  locale      TEXT NOT NULL DEFAULT 'ar',
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
CREATE INDEX ix_push_user ON push_devices(user_id);

-- ───────────────────────── Settings ─────────────────────────
-- Single-row donation settings. Values are empty until the association provides them.
CREATE TABLE donation_settings (
  id                  INTEGER PRIMARY KEY CHECK (id = 1),
  ccp_account         TEXT,     -- Algérie Poste CCP account number
  ccp_key             TEXT,
  ccp_holder          TEXT,
  bank_name           TEXT,
  bank_account        TEXT,     -- RIB
  bank_holder         TEXT,
  instructions_ar     TEXT,
  instructions_fr     TEXT,
  contact_phone       TEXT,
  contact_email       TEXT,
  updated_by          TEXT,
  updated_at          INTEGER NOT NULL
);
INSERT INTO donation_settings (id, updated_at) VALUES (1, 0);

-- Key/value application settings (association name, contact info, social links...).
CREATE TABLE application_settings (
  key         TEXT PRIMARY KEY,
  value       TEXT NOT NULL,
  is_public   INTEGER NOT NULL DEFAULT 1,
  updated_by  TEXT,
  updated_at  INTEGER NOT NULL
);

-- ───────────────────────── Audit & abuse protection ─────────────────────────
CREATE TABLE admin_audit (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  actor_id     TEXT,
  actor_role   TEXT,
  action       TEXT NOT NULL,
  entity_type  TEXT NOT NULL,
  entity_id    TEXT,
  detail       TEXT,
  ip           TEXT,
  created_at   INTEGER NOT NULL
);
CREATE INDEX ix_admin_audit_created ON admin_audit(created_at DESC);
CREATE INDEX ix_admin_audit_entity ON admin_audit(entity_type, entity_id);

CREATE TABLE rate_limits (
  key           TEXT NOT NULL,
  window_start  INTEGER NOT NULL,
  count         INTEGER NOT NULL,
  PRIMARY KEY (key, window_start)
) WITHOUT ROWID;
