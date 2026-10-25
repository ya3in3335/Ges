import { beforeEach, describe, expect, it } from 'vitest';
import { TestApp } from './helpers';

let app: TestApp;
beforeEach(() => { app = new TestApp(); });

describe('role-based access control', () => {
  it('blocks regular users from every admin endpoint', async () => {
    const u = await app.register(1);
    for (const [m, p] of [['GET', '/api/admin/overview'], ['GET', '/api/admin/users'], ['POST', '/api/admin/news'], ['PUT', '/api/admin/settings'], ['GET', '/api/admin/audit'], ['POST', '/api/admin/notifications']] as const) {
      const r = await app.req(m, p, { token: u.token, body: m === 'GET' ? undefined : {} });
      expect(r.status, `${m} ${p}`).toBe(403);
    }
  });

  it('lets editors publish news but not manage users or donations', async () => {
    const e = await app.register(1);
    app.setRole(e.id, 'EDITOR');
    expect((await app.req('POST', '/api/admin/news', { token: e.token, body: { titleAr: 'خبر جديد', bodyAr: 'نص', status: 'published' } })).status).toBe(201);
    expect((await app.req('GET', '/api/admin/users', { token: e.token })).status).toBe(403);
    expect((await app.req('PUT', '/api/admin/donations', { token: e.token, body: {} })).status).toBe(403);
  });

  it('prevents privilege escalation', async () => {
    const a = await app.register(1);
    const b = await app.register(2);
    const sa = await app.register(3);
    app.setRole(a.id, 'ADMIN');
    app.setRole(sa.id, 'SUPER_ADMIN');
    expect((await app.req('PATCH', `/api/admin/users/${b.id}`, { token: a.token, body: { role: 'SUPER_ADMIN' } })).status).toBe(403);
    expect((await app.req('PATCH', `/api/admin/users/${b.id}`, { token: a.token, body: { role: 'ADMIN' } })).status).toBe(403);
    expect((await app.req('PATCH', `/api/admin/users/${sa.id}`, { token: a.token, body: { status: 'suspended' } })).status).toBe(403);
    expect((await app.req('PATCH', `/api/admin/users/${a.id}`, { token: a.token, body: { role: 'SUPER_ADMIN' } })).status).toBe(403);
    expect((await app.req('PATCH', `/api/admin/users/${b.id}`, { token: a.token, body: { role: 'EDITOR' } })).status).toBe(200);
  });

  it('applies demotions immediately, even with a still-valid access token', async () => {
    const a = await app.register(1);
    app.setRole(a.id, 'ADMIN');
    expect((await app.req('GET', '/api/admin/users', { token: a.token })).status).toBe(200);
    app.setRole(a.id, 'USER');
    expect((await app.req('GET', '/api/admin/users', { token: a.token })).status).toBe(403);
  });

  it('a forged role claim in the token is ignored (role comes from the database)', async () => {
    const u = await app.register(1);
    const { signAccessToken } = await import('../src/lib/jwt');
    const payload = JSON.parse(Buffer.from(u.token.split('.')[1]!, 'base64url').toString());
    const forged = await signAccessToken('wrong-secret-wrong-secret-wrong-secret', { ...payload, role: 'SUPER_ADMIN' });
    expect((await app.req('GET', '/api/admin/users', { token: forged })).status).toBe(401);
  });

  it('suspension revokes sessions', async () => {
    const a = await app.register(1);
    const u = await app.register(2);
    app.setRole(a.id, 'ADMIN');
    await app.req('PATCH', `/api/admin/users/${u.id}`, { token: a.token, body: { status: 'suspended' } });
    expect((await app.req('GET', '/api/me', { token: u.token })).status).toBe(401);
  });

  it('masks phone numbers for ADMIN and shows them to SUPER_ADMIN only', async () => {
    const a = await app.register(1);
    app.setRole(a.id, 'ADMIN');
    const list = await app.req('GET', '/api/admin/users', { token: a.token });
    expect(list.body.items[0].phone_e164).toContain('*');
    app.setRole(a.id, 'SUPER_ADMIN');
    const list2 = await app.req('GET', '/api/admin/users', { token: a.token });
    expect(list2.body.items[0].phone_e164).toBe('+213550100001');
  });

  it('only SUPER_ADMIN may change application settings, and only https URLs', async () => {
    const a = await app.register(1);
    app.setRole(a.id, 'ADMIN');
    expect((await app.req('PUT', '/api/admin/settings', { token: a.token, body: { contact_phone: '0550' } })).status).toBe(403);
    app.setRole(a.id, 'SUPER_ADMIN');
    expect((await app.req('PUT', '/api/admin/settings', { token: a.token, body: { facebook_url: 'javascript:alert(1)' } })).status).toBe(400);
    expect((await app.req('PUT', '/api/admin/settings', { token: a.token, body: { not_a_key: 'x' } })).status).toBe(400);
    const ok = await app.req('PUT', '/api/admin/settings', { token: a.token, body: { association_name_ar: 'اسم الجمعية' } });
    expect(ok.body.settings.association_name_ar).toBe('اسم الجمعية');
  });

  it('answers CORS only for configured admin origins', async () => {
    const ok = await app.req('GET', '/api/health', { headers: { origin: 'https://admin.example.test' } });
    expect(ok.headers.get('access-control-allow-origin')).toBe('https://admin.example.test');
    const bad = await app.req('GET', '/api/health', { headers: { origin: 'https://evil.test' } });
    expect(bad.headers.get('access-control-allow-origin')).toBeNull();
  });
});
