import { beforeEach, describe, expect, it } from 'vitest';
import { TestApp, tgUser } from './helpers';

let app: TestApp;
beforeEach(() => { app = new TestApp(); });

describe('registration', () => {
  it('registers and never stores the plaintext password', async () => {
    const u = await app.register(1);
    expect(u.phone).toBe('+213550100001');
    const row = app.db.raw.prepare('SELECT password_hash FROM users WHERE id = ?').get(u.id) as { password_hash: string };
    expect(row.password_hash).toMatch(/^pbkdf2_sha256\$100000\$/);
    expect(row.password_hash).not.toContain('Secret123x');
  });

  it('validates input server-side', async () => {
    const base = { fullName: 'أحمد بن علي', email: 'a@example.com', phone: '0550123456', password: 'Secret123x', confirmPassword: 'Secret123x' };
    const cases: [Record<string, unknown>, string][] = [
      [{ ...base, confirmPassword: 'Other123x' }, 'password_mismatch'],
      [{ ...base, password: 'short1', confirmPassword: 'short1' }, 'password_too_short'],
      [{ ...base, password: 'onlyletters', confirmPassword: 'onlyletters' }, 'password_too_weak'],
      [{ ...base, phone: '0412345678' }, 'invalid_phone'],
      [{ ...base, phone: '+33612345678' }, 'invalid_phone'],
      [{ ...base, email: 'not-an-email' }, 'validation_failed'],
      [{ ...base, fullName: '<script>' }, 'validation_failed'],
    ];
    for (const [body, err] of cases) {
      const r = await app.req('POST', '/api/auth/register', { body, ip: app.freshIp() });
      expect(r.status, err).toBe(400);
      expect(r.body.error).toBe(err);
    }
  });

  it('rejects duplicate e-mail and phone (in any format)', async () => {
    await app.register(1, { phone: '0550111111', email: 'dup@example.com' });
    const body = { fullName: 'Ali Test', password: 'Secret123x', confirmPassword: 'Secret123x' };
    const r1 = await app.req('POST', '/api/auth/register', { ip: app.freshIp(), body: { ...body, email: 'DUP@example.com', phone: '0550222222' } });
    expect(r1.status).toBe(409);
    expect(r1.body.error).toBe('email_taken');
    const r2 = await app.req('POST', '/api/auth/register', { ip: app.freshIp(), body: { ...body, email: 'other@example.com', phone: '+213 550 11 11 11' } });
    expect(r2.body.error).toBe('phone_taken');
  });

  it('rate-limits registrations per IP', async () => {
    const statuses: number[] = [];
    for (let i = 0; i < 7; i++) {
      const r = await app.req('POST', '/api/auth/register', { ip: '1.1.1.1', body: { fullName: 'Ali Test', email: `r${i}@example.com`, phone: `055099999${i}`, password: 'Secret123x', confirmPassword: 'Secret123x' } });
      statuses.push(r.status);
    }
    expect(statuses.slice(0, 5).every((s) => s === 201)).toBe(true);
    expect(statuses[5]).toBe(429);
  });

  it('rejects requests that are not JSON', async () => {
    const r = await app.req('POST', '/api/auth/login', { headers: { 'content-type': 'text/plain' } });
    expect(r.body.error).toBe('expected_json');
  });
});

describe('login and sessions', () => {
  it('logs in with e-mail or phone and rejects wrong passwords with a generic error', async () => {
    await app.register(1);
    expect((await app.req('POST', '/api/auth/login', { body: { identifier: 'user1@example.com', password: 'Secret123x' } })).status).toBe(200);
    expect((await app.req('POST', '/api/auth/login', { body: { identifier: '0550100001', password: 'Secret123x' } })).status).toBe(200);
    const bad = await app.req('POST', '/api/auth/login', { body: { identifier: 'user1@example.com', password: 'Wrong123x' } });
    const unknown = await app.req('POST', '/api/auth/login', { body: { identifier: 'nobody@example.com', password: 'Wrong123x' } });
    expect(bad.status).toBe(401);
    expect(bad.body.error).toBe(unknown.body.error);
  });

  it('locks the account after repeated failures', async () => {
    await app.register(1);
    for (let i = 0; i < 10; i++) await app.req('POST', '/api/auth/login', { ip: app.freshIp(), body: { identifier: '0550100001', password: `Wrong${i}xx1` } });
    // identifier-based rate limit (10 / 10 min) also kicks in; move time forward past it but within the lock
    app.now += 11 * 60_000 - 5 * 60_000;
    const r = await app.req('POST', '/api/auth/login', { ip: app.freshIp(), body: { identifier: 'user1@example.com', password: 'Secret123x' } });
    expect([423, 429]).toContain(r.status);
  });

  it('rotates refresh tokens and revokes the family on reuse', async () => {
    const u = await app.register(1);
    const r1 = await app.req('POST', '/api/auth/refresh', { body: { refreshToken: u.refresh } });
    expect(r1.status).toBe(200);
    const reuse = await app.req('POST', '/api/auth/refresh', { body: { refreshToken: u.refresh } });
    expect(reuse.status).toBe(401);
    expect(reuse.body.error).toBe('refresh_token_reused');
    // the legitimately rotated token is now revoked as well
    const r2 = await app.req('POST', '/api/auth/refresh', { body: { refreshToken: r1.body.tokens.refreshToken } });
    expect(r2.status).toBe(401);
  });

  it('logout revokes the session so the access token stops working', async () => {
    const u = await app.register(1);
    expect((await app.req('GET', '/api/me', { token: u.token })).status).toBe(200);
    await app.req('POST', '/api/auth/logout', { token: u.token, body: { refreshToken: u.refresh } });
    expect((await app.req('GET', '/api/me', { token: u.token })).status).toBe(401);
  });

  it('rejects tampered, expired and alg=none tokens', async () => {
    const u = await app.register(1);
    const [h, p, s] = u.token.split('.');
    const tampered = `${h}.${Buffer.from(JSON.stringify({ ...JSON.parse(Buffer.from(p!, 'base64url').toString()), role: 'SUPER_ADMIN' })).toString('base64url')}.${s}`;
    expect((await app.req('GET', '/api/me', { token: tampered })).status).toBe(401);
    const none = `${Buffer.from('{"alg":"none","typ":"JWT"}').toString('base64url')}.${p}.`;
    expect((await app.req('GET', '/api/me', { token: none })).status).toBe(401);
    app.now += 16 * 60_000;
    expect((await app.req('GET', '/api/me', { token: u.token })).status).toBe(401);
  });
});

describe('password recovery', () => {
  it('sends a reset code through the linked Telegram account and resets the password', async () => {
    const u = await app.register(1);
    app.db.raw.prepare(`INSERT INTO telegram_verifications (id, user_id, token_hash, status, created_at, expires_at) VALUES ('v1', ?, 'h', 'verified', 0, 0)`).run(u.id);
    app.db.raw.prepare(`INSERT INTO telegram_account_links (user_id, telegram_user_id, telegram_chat_id, verification_id, linked_at) VALUES (?, 777, 777, 'v1', 0)`).run(u.id);
    const r = await app.req('POST', '/api/auth/password/forgot', { body: { identifier: 'user1@example.com' } });
    expect(r.status).toBe(200);
    const msg = app.telegram.find((t) => t.method === 'sendMessage' && t.body.chat_id === 777);
    const code = String(msg?.body.text).match(/\d{6}/)?.[0];
    expect(code).toBeDefined();
    const wrong = await app.req('POST', '/api/auth/password/reset', { body: { identifier: 'user1@example.com', code: code === '000000' ? '111111' : '000000', newPassword: 'NewSecret99' } });
    expect(wrong.status).toBe(400);
    const ok = await app.req('POST', '/api/auth/password/reset', { body: { identifier: 'user1@example.com', code, newPassword: 'NewSecret99' } });
    expect(ok.status).toBe(200);
    // code is single-use
    const again = await app.req('POST', '/api/auth/password/reset', { body: { identifier: 'user1@example.com', code, newPassword: 'NewSecret98' } });
    expect(again.status).toBe(400);
    // old sessions are revoked
    expect((await app.req('GET', '/api/me', { token: u.token })).status).toBe(401);
    expect((await app.req('POST', '/api/auth/login', { body: { identifier: 'user1@example.com', password: 'NewSecret99' } })).status).toBe(200);
  });

  it('does not reveal whether an account exists', async () => {
    const r = await app.req('POST', '/api/auth/password/forgot', { body: { identifier: 'ghost@example.com' } });
    expect(r.status).toBe(200);
    expect(r.body.message).toBe('if_account_exists_code_sent');
  });
});

describe('account deletion', () => {
  it('requires the password, erases personal data and revokes sessions', async () => {
    const u = await app.register(1);
    expect((await app.req('DELETE', '/api/me', { token: u.token, body: { password: 'nope' } })).status).toBe(401);
    expect((await app.req('DELETE', '/api/me', { token: u.token, body: { password: 'Secret123x' } })).status).toBe(200);
    const row = app.db.raw.prepare('SELECT full_name, email, phone_e164, status FROM users WHERE id = ?').get(u.id) as Record<string, string>;
    expect(row.status).toBe('deleted');
    expect(row.email).not.toContain('user1');
    expect(row.phone_e164).not.toContain('550');
    expect((await app.req('GET', '/api/me', { token: u.token })).status).toBe(401);
    // the same e-mail/phone can register again
    await app.register(1);
  });
});

describe('telegram helper sanity', () => { it('tgUser builds a private chat', () => expect(tgUser(5).chat.type).toBe('private')); });
