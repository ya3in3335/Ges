import { beforeEach, describe, expect, it } from 'vitest';
import { TestApp } from './helpers';

let app: TestApp;
let sa: { token: string; id: string };
beforeEach(async () => {
  app = new TestApp();
  sa = await app.register(90);
  app.setRole(sa.id, 'SUPER_ADMIN');
});

const newCode = async (token: string, body: Record<string, unknown>) => app.req('POST', '/api/admin/activation-codes', { token, body });
const activate = (code: string, ip = app.freshIp()) => app.req('POST', '/api/auth/activate', { body: { code, deviceName: 'Pixel' }, ip });

describe('supervisor device activation', () => {
  it('creates a staff supervisor and activates a device once, without any password', async () => {
    const c = await newCode(sa.token, { name: 'أحمد المشرف', role: 'EDITOR' });
    expect(c.status).toBe(201);
    expect(c.body.code).toMatch(/^\d{8}$/);
    // only the hash is stored
    expect(JSON.stringify(app.db.raw.prepare('SELECT * FROM activation_codes').all())).not.toContain(c.body.code);
    const a = await activate(c.body.code);
    expect(a.status).toBe(201);
    expect(a.body.user.role).toBe('EDITOR');
    const t = a.body.tokens;
    expect(t.refreshTokenExpiresAt - app.now).toBeGreaterThan(300 * 86_400_000);
    // the device can work as an editor
    expect((await app.req('POST', '/api/admin/news', { token: t.accessToken, body: { titleAr: 'خبر من الهاتف', bodyAr: 'نص' } })).status).toBe(201);
    // refresh keeps the long-lived policy
    const r = await app.req('POST', '/api/auth/refresh', { body: { refreshToken: t.refreshToken } });
    expect(r.body.tokens.refreshTokenExpiresAt - app.now).toBeGreaterThan(300 * 86_400_000);
    // staff accounts cannot log in with a password
    expect((await app.req('POST', '/api/auth/login', { body: { identifier: `staff-${c.body.userId}@staff.invalid`, password: '!' } })).status).toBe(401);
  });

  it('codes are single-use and expire after 48 hours', async () => {
    const c = await newCode(sa.token, { name: 'Sara', role: 'EDITOR' });
    expect((await activate(c.body.code)).status).toBe(201);
    expect((await activate(c.body.code)).body.error).toBe('invalid_code');
    const c2 = await newCode(sa.token, { name: 'Nour', role: 'EDITOR' });
    app.now += 49 * 3600_000;
    expect((await activate(c2.body.code)).body.error).toBe('invalid_code');
  });

  it('rate-limits guessing per IP', async () => {
    const statuses: number[] = [];
    for (let i = 0; i < 12; i++) statuses.push((await activate(String(10_000_000 + i), '7.7.7.7')).status);
    expect(statuses.slice(0, 10).every((s) => s === 400)).toBe(true);
    expect(statuses[10]).toBe(429);
  });

  it('respects role hierarchy and refuses members', async () => {
    const admin = await app.register(91);
    app.setRole(admin.id, 'ADMIN');
    expect((await newCode(admin.token, { name: 'Test', role: 'SUPER_ADMIN' })).status).toBe(403);
    expect((await newCode(admin.token, { name: 'Test', role: 'ADMIN' })).status).toBe(403);
    expect((await newCode(admin.token, { name: 'Test', role: 'EDITOR' })).status).toBe(201);
    const editor = await app.register(92);
    app.setRole(editor.id, 'EDITOR');
    expect((await newCode(editor.token, { name: 'Test', role: 'EDITOR' })).status).toBe(403);
    const member = await app.register(93);
    expect((await newCode(sa.token, { userId: member.id })).body.error).toBe('not_staff');
  });

  it('issues a code for an existing staff account (e.g. yourself)', async () => {
    const c = await newCode(sa.token, { userId: sa.id });
    const a = await activate(c.body.code);
    expect(a.body.user.id).toBe(sa.id);
  });

  it('lists and revokes devices immediately', async () => {
    const c = await newCode(sa.token, { name: 'Karim', role: 'EDITOR' });
    const a = await activate(c.body.code);
    const list = await app.req('GET', '/api/admin/devices', { token: sa.token });
    expect(list.body.items).toHaveLength(1);
    expect(list.body.items[0].full_name).toBe('Karim');
    expect((await app.req('DELETE', `/api/admin/devices/${list.body.items[0].id}`, { token: sa.token })).status).toBe(200);
    expect((await app.req('GET', '/api/me', { token: a.body.tokens.accessToken })).status).toBe(401);
    expect((await app.req('POST', '/api/auth/refresh', { body: { refreshToken: a.body.tokens.refreshToken } })).status).toBe(401);
    // audit trail names the supervisor
    expect(app.db.raw.prepare(`SELECT COUNT(*) AS n FROM admin_audit WHERE action IN ('create_activation_code','device_activated','revoke_device')`).get()).toEqual({ n: 3 });
  });

  it('suspending the supervisor also disconnects the device', async () => {
    const c = await newCode(sa.token, { name: 'Lina', role: 'EDITOR' });
    const a = await activate(c.body.code);
    await app.req('PATCH', `/api/admin/users/${c.body.userId}`, { token: sa.token, body: { status: 'suspended' } });
    expect((await app.req('GET', '/api/me', { token: a.body.tokens.accessToken })).status).toBe(401);
  });

  it('staff accounts are not counted as members', async () => {
    await newCode(sa.token, { name: 'Omar', role: 'EDITOR' });
    expect((await app.req('GET', '/api/admin/overview', { token: sa.token })).body.users).toBe(1);
  });

  it('general supervisors cannot disconnect each other, only themselves and lower roles', async () => {
    app.env.TELEGRAM_APPROVER_IDS = '111';
    const a = await activate((await newCode(sa.token, { name: 'Smail', role: 'SUPER_ADMIN' })).body.code);
    const b = await activate((await newCode(sa.token, { name: 'Yacin', role: 'SUPER_ADMIN' })).body.code);
    const ed = await activate((await newCode(sa.token, { name: 'Karim', role: 'EDITOR' })).body.code);
    const list = await app.req('GET', '/api/admin/devices', { token: a.body.tokens.accessToken });
    const row = (n: string) => list.body.items.find((d: { full_name: string }) => d.full_name === n);
    expect(row('Smail')).toMatchObject({ current: true, canRevoke: true });
    expect(row('Yacin')).toMatchObject({ current: false, canRevoke: false });
    expect(row('Karim')).toMatchObject({ canRevoke: true });
    const r = await app.req('DELETE', `/api/admin/devices/${row('Yacin').id}`, { token: a.body.tokens.accessToken });
    expect(r.status).toBe(403);
    expect(r.body.error).toBe('cannot_revoke_peer_device');
    expect((await app.req('GET', '/api/me', { token: b.body.tokens.accessToken })).status).toBe(200);
    // Lower role: allowed, and the approvers are told who did it.
    app.telegram.length = 0;
    expect((await app.req('DELETE', `/api/admin/devices/${row('Karim').id}`, { token: a.body.tokens.accessToken })).status).toBe(200);
    expect((await app.req('GET', '/api/me', { token: ed.body.tokens.accessToken })).status).toBe(401);
    const msg = app.telegram.find((m) => m.method === 'sendMessage');
    expect(msg?.body.chat_id).toBe(111);
    expect(msg?.body.text).toContain('Karim');
    expect(msg?.body.text).toContain('Smail');
    // Own device: allowed.
    expect((await app.req('DELETE', `/api/admin/devices/${row('Smail').id}`, { token: a.body.tokens.accessToken })).status).toBe(200);
  });
});
