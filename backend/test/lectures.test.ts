import { beforeEach, describe, expect, it } from 'vitest';
import { TestApp } from './helpers';

let app: TestApp;
let editor: { token: string; id: string };
const H = 3600_000;
const profile = {
  firstName: 'أحمد', lastName: 'بن علي', gender: 'male', birthYear: 1995, wilaya: 'وهران',
  maritalStatus: 'married', childrenCount: 2, occupation: 'worker', occupationDetail: 'أستاذ',
};

beforeEach(async () => {
  app = new TestApp();
  editor = await app.register(99);
  app.setRole(editor.id, 'EDITOR');
});

async function lecture(extra: Record<string, unknown> = {}) {
  const r = await app.req('POST', '/api/admin/lectures', { token: editor.token, body: {
    titleAr: 'محاضرة عن التربية', speaker: 'د. محمد', location: 'قاعة الجمعية', startsAt: app.now + 24 * H, endsAt: app.now + 26 * H,
    status: 'published', ...extra,
  } });
  expect(r.status).toBe(201);
  return r.body.id as string;
}

describe('lectures', () => {
  it('only staff can create, and the schedule shows published ones', async () => {
    const user = await app.register(1);
    expect((await app.req('POST', '/api/admin/lectures', { token: user.token, body: { titleAr: 'xxx', startsAt: 1, endsAt: 2 } })).status).toBe(403);
    const id = await lecture();
    await lecture({ titleAr: 'مسودة', status: 'draft' });
    const list = await app.req('GET', '/api/lectures');
    expect(list.body.items.map((l: { id: string }) => l.id)).toEqual([id]);
    expect(list.body.items[0].registrationOpen).toBe(true);
  });

  it('refuses registration until the e-mail or the phone is verified', async () => {
    const id = await lecture();
    const u = await app.register(2);
    const r = await app.req('POST', `/api/lectures/${id}/register`, { token: u.token, body: { profile } });
    expect(r.status).toBe(403);
    expect(r.body.error).toBe('contact_not_verified');
    expect((await app.req('GET', `/api/lectures/${id}`, { token: u.token })).body.contactVerified).toBe(false);
    app.db.raw.prepare('UPDATE users SET email_verified_at = ? WHERE id = ?').run(app.now, u.id);
    expect((await app.req('POST', `/api/lectures/${id}/register`, { token: u.token, body: { profile } })).status).toBe(201);
  });

  it('stores the form with the account phone, prefills the next one, and organisers see it', async () => {
    const id = await lecture();
    const u = await app.register(3);
    app.markVerified(u.id);
    const r = await app.req('POST', `/api/lectures/${id}/register`, { token: u.token, body: { profile: { ...profile, phone: '0555000000' }, note: 'سؤال' } });
    expect(r.status).toBe(201);
    const saved = await app.req('GET', '/api/me/lecture-profile', { token: u.token });
    expect(saved.body.profile).toMatchObject({ firstName: 'أحمد', childrenCount: 2, occupation: 'worker' });
    expect(saved.body.phone).toBe(u.phone);
    const regs = await app.req('GET', `/api/admin/lectures/${id}/registrations`, { token: editor.token });
    expect(regs.body.items).toHaveLength(1);
    expect(regs.body.items[0]).toMatchObject({ firstName: 'أحمد', lastName: 'بن علي', phone: u.phone, childrenCount: 2, note: 'سؤال' });
    expect((await app.req('GET', `/api/lectures/${id}`, { token: u.token })).body.myRegistration).toBe('registered');
    expect((await app.req('GET', `/api/admin/lectures/${id}/registrations`, { token: u.token })).status).toBe(403);
  });

  it('validates the conditional questions', async () => {
    const id = await lecture();
    const u = await app.register(4);
    app.markVerified(u.id);
    const bad = async (p: Record<string, unknown>) => (await app.req('POST', `/api/lectures/${id}/register`, { token: u.token, body: { profile: { ...profile, ...p } } })).status;
    expect(await bad({ maritalStatus: 'married', childrenCount: null })).toBe(400);
    expect(await bad({ occupation: 'university_student', occupationDetail: '' })).toBe(400);
    expect(await bad({ birthYear: 2030 })).toBe(400);
    expect(await bad({ firstName: '<script>' })).toBe(400);
    // Single: children are not asked and stored as null.
    expect(await bad({ maritalStatus: 'single', childrenCount: 5, occupation: 'unemployed', occupationDetail: null })).toBe(201);
    const regs = await app.req('GET', `/api/admin/lectures/${id}/registrations`, { token: editor.token });
    expect(regs.body.items[0].childrenCount).toBeNull();
  });

  it('respects capacity, cancellation and closing', async () => {
    const id = await lecture({ capacity: 1 });
    const [a, b] = [await app.register(5), await app.register(6)];
    app.markVerified(a.id); app.markVerified(b.id);
    expect((await app.req('POST', `/api/lectures/${id}/register`, { token: a.token, body: { profile } })).status).toBe(201);
    expect((await app.req('POST', `/api/lectures/${id}/register`, { token: a.token, body: { profile } })).status).toBe(201); // update own answers
    expect((await app.req('POST', `/api/lectures/${id}/register`, { token: b.token, body: { profile } })).body.error).toBe('lecture_full');
    expect((await app.req('POST', `/api/lectures/${id}/cancel`, { token: a.token })).status).toBe(200);
    expect((await app.req('POST', `/api/lectures/${id}/register`, { token: b.token, body: { profile } })).status).toBe(201);
    await app.req('PATCH', `/api/admin/lectures/${id}`, { token: editor.token, body: { registrationOpen: false } });
    expect((await app.req('POST', `/api/lectures/${id}/register`, { token: a.token, body: { profile } })).body.error).toBe('registration_closed');
    expect((await app.req('PATCH', `/api/admin/lectures/${id}/registrations/${b.id}`, { token: editor.token, body: { status: 'attended' } })).status).toBe(200);
  });

  it('account deletion erases the form answers', async () => {
    const id = await lecture();
    const u = await app.register(7);
    app.markVerified(u.id);
    await app.req('POST', `/api/lectures/${id}/register`, { token: u.token, body: { profile } });
    expect((await app.req('DELETE', '/api/me', { token: u.token, body: { password: 'Secret123x' } })).status).toBe(200);
    expect(app.db.raw.prepare('SELECT COUNT(*) n FROM lecture_registrations').get()).toEqual({ n: 0 });
    expect(app.db.raw.prepare('SELECT COUNT(*) n FROM member_profiles').get()).toEqual({ n: 0 });
  });
});
