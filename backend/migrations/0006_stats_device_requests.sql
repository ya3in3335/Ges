-- The census admin app is activated from the Telegram bot (no e-mail / password), like the supervisors' app.
-- Additive only.
ALTER TABLE device_requests ADD COLUMN kind TEXT NOT NULL DEFAULT 'admin' CHECK (kind IN ('admin','stats'));
ALTER TABLE device_requests ADD COLUMN stats_level TEXT CHECK (stats_level IN ('manager','officer'));
