-- Census ("الإحصاء"): meetings, registrations and attendance, used by the two statistics apps.
-- Additive only: no existing table or row is changed or removed.

-- Members (any role) allowed to run the census. ADMIN and SUPER_ADMIN always are (as managers).
-- officer: registers people and confirms attendance. manager: also meetings, corrections, exports, officers.
CREATE TABLE stats_staff (
  user_id     TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  level       TEXT NOT NULL DEFAULT 'officer' CHECK (level IN ('manager','officer')),
  granted_by  TEXT REFERENCES users(id) ON DELETE SET NULL,
  created_at  INTEGER NOT NULL
);

CREATE TABLE stats_meetings (
  id                TEXT PRIMARY KEY,
  title             TEXT NOT NULL,
  description       TEXT,
  location          TEXT NOT NULL,
  starts_at         INTEGER NOT NULL,
  -- Attendance can be confirmed between these two times; after late_after it counts as "late".
  checkin_opens_at  INTEGER NOT NULL,
  checkin_closes_at INTEGER NOT NULL,
  late_after        INTEGER NOT NULL,
  registration_open INTEGER NOT NULL DEFAULT 1,
  status            TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','closed','cancelled')),
  closed_at         INTEGER,
  closed_by         TEXT REFERENCES users(id) ON DELETE SET NULL,
  created_by        TEXT REFERENCES users(id) ON DELETE SET NULL,
  created_at        INTEGER NOT NULL,
  updated_at        INTEGER NOT NULL,
  CHECK (checkin_closes_at > checkin_opens_at)
);
CREATE INDEX ix_stats_meetings_time ON stats_meetings(status, starts_at);

-- One row per person and meeting. user_id is set when members register themselves,
-- NULL when a census officer registers someone without an account.
CREATE TABLE stats_registrations (
  id             TEXT PRIMARY KEY,
  meeting_id     TEXT NOT NULL REFERENCES stats_meetings(id) ON DELETE CASCADE,
  reg_no         INTEGER NOT NULL,
  user_id        TEXT REFERENCES users(id) ON DELETE SET NULL,
  first_name     TEXT NOT NULL,
  last_name      TEXT NOT NULL,
  gender         TEXT CHECK (gender IN ('male','female')),
  wilaya_code    INTEGER NOT NULL CHECK (wilaya_code BETWEEN 1 AND 58),
  commune_id     INTEGER NOT NULL,
  birth_date     TEXT NOT NULL,             -- YYYY-MM-DD
  marital_status TEXT NOT NULL CHECK (marital_status IN ('single','married','divorced','widowed')),
  -- Normalised name + birth date: the same person cannot be registered twice in one meeting.
  person_key     TEXT NOT NULL,
  status         TEXT NOT NULL DEFAULT 'registered' CHECK (status IN ('registered','present','late','absent','cancelled')),
  -- Random value behind the QR code (the code carries an HMAC of it, never personal data).
  checkin_nonce  TEXT NOT NULL,
  checked_in_at  INTEGER,
  checked_in_by  TEXT REFERENCES users(id) ON DELETE SET NULL,
  checkin_method TEXT CHECK (checkin_method IN ('qr','manual','number')),
  source         TEXT NOT NULL CHECK (source IN ('member','staff')),
  created_by     TEXT REFERENCES users(id) ON DELETE SET NULL,
  created_at     INTEGER NOT NULL,
  updated_at     INTEGER NOT NULL
);
CREATE UNIQUE INDEX ux_stats_reg_no ON stats_registrations(meeting_id, reg_no);
CREATE UNIQUE INDEX ux_stats_reg_user ON stats_registrations(meeting_id, user_id) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX ux_stats_reg_person ON stats_registrations(meeting_id, person_key);
CREATE INDEX ix_stats_reg_meeting ON stats_registrations(meeting_id, status);
CREATE INDEX ix_stats_reg_user ON stats_registrations(user_id);
CREATE INDEX ix_stats_reg_place ON stats_registrations(wilaya_code, commune_id);
CREATE INDEX ix_stats_reg_updated ON stats_registrations(updated_at);

-- The census form prefills from the member's existing profile (shared with the lectures form).
ALTER TABLE member_profiles ADD COLUMN wilaya_code INTEGER;
ALTER TABLE member_profiles ADD COLUMN commune_id INTEGER;
ALTER TABLE member_profiles ADD COLUMN birth_date TEXT;
