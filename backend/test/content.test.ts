import { beforeEach, describe, expect, it } from 'vitest';
import { TestApp } from './helpers';
import { sniffType } from '../src/routes/media';

let app: TestApp;
let admin: { token: string; id: string };
beforeEach(async () => {
  app = new TestApp();
  admin = await app.register(99);
  app.setRole(admin.id, 'ADMIN');
});

describe('news', () => {
  it('publishes, lists, searches (literal LIKE) and hides drafts', async () => {
    await app.req('POST', '/api/admin/news', { token: admin.token, body: { titleAr: 'حملة الشتاء 100%', bodyAr: 'توزيع أغطية', status: 'published', category: 'campaign' } });
    await app.req('POST', '/api/admin/news', { token: admin.token, body: { titleAr: 'مسودة سرية', bodyAr: 'لا تنشر', status: 'draft' } });
    const list = await app.req('GET', '/api/news');
    expect(list.body.items).toHaveLength(1);
    expect((await app.req('GET', '/api/news?q=100%25')).body.items).toHaveLength(1);
    expect((await app.req('GET', '/api/news?q=%25')).body.items).toHaveLength(1);
    expect((await app.req('GET', '/api/news?q=_')).body.items).toHaveLength(0);
    expect((await app.req('GET', `/api/news?q=${encodeURIComponent("' OR 1=1 --")}`)).body.items).toHaveLength(0);
    expect((await app.req('GET', '/api/news?category=campaign')).body.items).toHaveLength(1);
  });

  it('paginates', async () => {
    for (let i = 0; i < 25; i++) {
      app.now += 1000;
      await app.req('POST', '/api/admin/news', { token: admin.token, body: { titleAr: `خبر رقم ${i}`, bodyAr: 'نص', status: 'published' } });
    }
    const p1 = await app.req('GET', '/api/news?page=1&pageSize=10');
    const p3 = await app.req('GET', '/api/news?page=3&pageSize=10');
    expect(p1.body.items).toHaveLength(10);
    expect(p1.body.hasMore).toBe(true);
    expect(p3.body.items).toHaveLength(5);
    expect(p3.body.hasMore).toBe(false);
  });
});

describe('activities', () => {
  it('registers participants and enforces capacity atomically', async () => {
    const c = await app.req('POST', '/api/admin/activities', { token: admin.token, body: { titleAr: 'قافلة تضامنية', descriptionAr: 'وصف', startsAt: app.now + 3600_000, endsAt: app.now + 7200_000, capacity: 2, status: 'published' } });
    const id = c.body.id;
    const users = await Promise.all([1, 2, 3].map((i) => app.register(i)));
    const rs = await Promise.all(users.map((u) => app.req('POST', `/api/activities/${id}/register`, { token: u.token })));
    expect(rs.filter((r) => r.status === 201)).toHaveLength(2);
    expect(rs.find((r) => r.status === 409)!.body.error).toBe('activity_full');
    expect((await app.req('POST', `/api/activities/${id}/register`, { token: users[0]!.token })).body.error).toBe('already_registered');
    const detail = await app.req('GET', `/api/activities/${id}`, { token: users[0]!.token });
    expect(detail.body.item.registeredCount).toBe(2);
    expect(detail.body.myRegistration).toBe('registered');
  });

  it('classifies upcoming / ongoing / completed', async () => {
    for (const [s, e] of [[1, 2], [-1, 1], [-3, -2]]) {
      await app.req('POST', '/api/admin/activities', { token: admin.token, body: { titleAr: `نشاط ${s}`, descriptionAr: 'و', startsAt: app.now + s! * 3600_000, endsAt: app.now + e! * 3600_000, status: 'published' } });
    }
    for (const ph of ['upcoming', 'ongoing', 'completed']) {
      const r = await app.req('GET', `/api/activities?phase=${ph}`);
      expect(r.body.items).toHaveLength(1);
      expect(r.body.items[0].phase).toBe(ph);
    }
  });
});

describe('volunteers', () => {
  it('handles application, approval (role change) and protects others’ data', async () => {
    const u = await app.register(1);
    const other = await app.register(2);
    const body = { fullName: 'متطوع تجريبي', contact: '0550100001', skills: 'إسعافات أولية', availability: 'نهاية الأسبوع' };
    expect((await app.req('POST', '/api/volunteers', { token: u.token, body })).status).toBe(201);
    expect((await app.req('POST', '/api/volunteers', { token: u.token, body })).status).toBe(409);
    expect((await app.req('GET', '/api/volunteers/me', { token: other.token })).body.application).toBeNull();
    expect((await app.req('GET', '/api/admin/volunteers', { token: u.token })).status).toBe(403);
    const list = await app.req('GET', '/api/admin/volunteers?status=pending', { token: admin.token });
    expect(list.body.items).toHaveLength(1);
    await app.req('PATCH', `/api/admin/volunteers/${list.body.items[0].id}`, { token: admin.token, body: { status: 'approved' } });
    const me = await app.req('GET', '/api/me', { token: u.token });
    expect(me.body.user.role).toBe('VOLUNTEER');
    expect((await app.req('GET', '/api/volunteers/me', { token: u.token })).body.application.status).toBe('approved');
    // notification recorded honestly as not delivered when FCM is not configured
    expect(app.db.raw.prepare(`SELECT delivery FROM notifications WHERE target_user_id = ?`).get(u.id)).toEqual({ delivery: 'not_configured' });
  });
});

describe('donations and settings', () => {
  it('starts empty and validates admin-provided financial details', async () => {
    const d = await app.req('GET', '/api/donations');
    expect(d.body.donation.ccpAccount).toBeNull();
    expect((await app.req('PUT', '/api/admin/donations', { token: admin.token, body: { ccpAccount: 'abc' } })).status).toBe(400);
    const ok = await app.req('PUT', '/api/admin/donations', { token: admin.token, body: { ccpAccount: '0012345678', ccpKey: '12', instructionsAr: 'تعليمات' } });
    expect(ok.body.donation.ccpAccount).toBe('0012345678');
  });

  it('serves the aggregated home payload in one request', async () => {
    const r = await app.req('GET', '/api/home');
    expect(r.status).toBe(200);
    expect(r.body).toHaveProperty('featuredNews');
    expect(r.body).toHaveProperty('activePolls');
    expect(r.body.donationAvailable).toBe(false);
  });
});

describe('media validation', () => {
  it('detects real file types from magic bytes', () => {
    expect(sniffType(new Uint8Array([0xff, 0xd8, 0xff, 0xe0]))?.mime).toBe('image/jpeg');
    expect(sniffType(new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))?.mime).toBe('image/png');
    expect(sniffType(new TextEncoder().encode('RIFF\0\0\0\0WEBPVP8 '))?.mime).toBe('image/webp');
    expect(sniffType(new TextEncoder().encode('\0\0\0\x18ftypmp42'))?.kind).toBe('video');
    expect(sniffType(new TextEncoder().encode('<svg xmlns='))).toBeNull();
    expect(sniffType(new TextEncoder().encode('<?php echo 1;'))).toBeNull();
  });

  it('refuses uploads when R2 is not configured, and non-https external URLs', async () => {
    expect((await app.req('POST', '/api/admin/media', { token: admin.token, body: { kind: 'video', url: 'http://insecure.test/v.mp4' } })).status).toBe(400);
    expect((await app.req('POST', '/api/admin/media', { token: admin.token, body: { kind: 'video', url: 'https://www.youtube.com/watch?v=abc', category: 'حملات' } })).status).toBe(201);
    const list = await app.req('GET', '/api/media?kind=video');
    expect(list.body.items).toHaveLength(1);
    expect(list.body.categories).toEqual(['حملات']);
  });
});

describe('API validation', () => {
  it('returns 404 / 405 for unknown routes and methods', async () => {
    expect((await app.req('GET', '/api/nope')).status).toBe(404);
    expect((await app.req('DELETE', '/api/news')).status).toBe(405);
  });

  it('rejects malformed JSON and oversized bodies', async () => {
    const bad = await app.req('POST', '/api/auth/login', { headers: { 'content-type': 'application/json' } });
    expect(bad.status).toBe(400);
    const big = await app.req('POST', '/api/auth/login', { body: { identifier: 'x'.repeat(70_000), password: 'x' } });
    expect(big.status).toBe(413);
  });

  it('rejects unknown fields on strict update schemas', async () => {
    const u = await app.register(1);
    const r = await app.req('PATCH', '/api/me', { token: u.token, body: { role: 'SUPER_ADMIN' } });
    expect(r.status).toBe(400);
    expect((await app.req('GET', '/api/me', { token: u.token })).body.user.role).toBe('USER');
  });
});
