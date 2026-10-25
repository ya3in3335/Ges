import { describe, expect, it } from 'vitest';
import { migratedDb } from './d1shim';

describe('database migrations', () => {
  const db = migratedDb();
  const tables = (db.raw.prepare(`SELECT name FROM sqlite_master WHERE type = 'table'`).all() as { name: string }[]).map((t) => t.name);

  it.each([
    'users', 'auth_sessions', 'telegram_verifications', 'telegram_account_links', 'verification_audit', 'news', 'activities',
    'activity_registrations', 'polls', 'poll_options', 'votes', 'poll_participation', 'poll_eligible_members', 'volunteers',
    'media', 'notifications', 'donation_settings', 'admin_audit', 'application_settings', 'channel_outbox', 'rate_limits',
  ])('creates table %s', (t) => expect(tables).toContain(t));

  it('passes the SQLite integrity and foreign key checks', () => {
    expect(db.raw.prepare('PRAGMA integrity_check').get()).toEqual({ integrity_check: 'ok' });
    expect(db.raw.prepare('PRAGMA foreign_key_check').all()).toEqual([]);
  });

  it('seeds exactly one empty donation settings row (no invented details)', () => {
    const rows = db.raw.prepare('SELECT * FROM donation_settings').all() as Record<string, unknown>[];
    expect(rows).toHaveLength(1);
    expect(rows[0]!.ccp_account).toBeNull();
    expect(rows[0]!.bank_account).toBeNull();
  });

  it('enforces role values with a CHECK constraint', () => {
    expect(() => db.raw.prepare(
      `INSERT INTO users (id, full_name, email, phone_e164, password_hash, role, created_at, updated_at) VALUES ('x','n','e@x.y','+213550000000','h','GOD',0,0)`,
    ).run()).toThrow(/CHECK/);
  });
});
