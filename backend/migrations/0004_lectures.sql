-- Lectures and meetings with a registration form.

CREATE TABLE lectures (
  id               TEXT PRIMARY KEY,
  kind             TEXT NOT NULL DEFAULT 'lecture' CHECK (kind IN ('lecture','meeting','training')),
  title_ar         TEXT NOT NULL,
  title_fr         TEXT,
  description_ar   TEXT,
  description_fr   TEXT,
  speaker          TEXT,
  location         TEXT,
  online_url       TEXT,
  starts_at        INTEGER NOT NULL,
  ends_at          INTEGER NOT NULL,
  capacity         INTEGER,
  registration_open INTEGER NOT NULL DEFAULT 1,
  status           TEXT NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','published','cancelled')),
  created_by       TEXT REFERENCES users(id) ON DELETE SET NULL,
  created_at       INTEGER NOT NULL,
  updated_at       INTEGER NOT NULL,
  CHECK (ends_at > starts_at)
);
CREATE INDEX ix_lectures_time ON lectures(status, starts_at);

-- Answers to the registration form, kept per member so the next form is prefilled.
CREATE TABLE member_profiles (
  user_id           TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  first_name        TEXT NOT NULL,
  last_name         TEXT NOT NULL,
  gender            TEXT CHECK (gender IN ('male','female')),
  birth_year        INTEGER,
  wilaya            TEXT,
  marital_status    TEXT CHECK (marital_status IN ('single','married','divorced','widowed')),
  children_count    INTEGER,
  occupation        TEXT CHECK (occupation IN ('university_student','pupil','worker','unemployed','retired','other')),
  occupation_detail TEXT,
  updated_at        INTEGER NOT NULL
);

-- One registration per member and lecture; the form answers are a snapshot taken at registration time.
CREATE TABLE lecture_registrations (
  lecture_id        TEXT NOT NULL REFERENCES lectures(id) ON DELETE CASCADE,
  user_id           TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status            TEXT NOT NULL DEFAULT 'registered' CHECK (status IN ('registered','cancelled','attended')),
  first_name        TEXT NOT NULL,
  last_name         TEXT NOT NULL,
  phone_e164        TEXT NOT NULL,
  email             TEXT,
  gender            TEXT,
  birth_year        INTEGER,
  wilaya            TEXT,
  marital_status    TEXT,
  children_count    INTEGER,
  occupation        TEXT,
  occupation_detail TEXT,
  note              TEXT,
  created_at        INTEGER NOT NULL,
  updated_at        INTEGER NOT NULL,
  PRIMARY KEY (lecture_id, user_id)
);
CREATE INDEX ix_lecreg_user ON lecture_registrations(user_id);
