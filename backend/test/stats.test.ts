import { beforeEach, describe, expect, it } from 'vitest';
import { TestApp } from './helpers';
import { ageOn, personKey, validBirthDate } from '../src/routes/stats';

let app: TestApp;
let admin: { token: string; id: string; refresh: string };
const H = 3600_000;
const MIN = 60_000;
// Oran (31) / Sidi Chami (commune 1134) in the bundled geography data.
const person = { firstName: 'أحمد', lastName: 'بن علي', gender: 'male', wilayaCode: 31, communeId: 1134, birthDate: '1995-04-12', maritalStatus: 'married' };

beforeEach(async () => {
  app = new TestApp();
  admin = await app.register(99);
  app.setRole(admin.id, 'ADMIN');
});

/** Moves the clock forward; the admin's 15-minute access token is renewed like the apps do. */
async function advance(ms: number) {
  app.now += ms;
  const r = await app.req('POST', '/api/auth/refresh', { body: { refreshToken: admin.refresh } });
  admin = { ...admin, token: r.body.tokens.accessToken, refresh: r.body.tokens.refreshToken };
}

async function meeting(extra: Record<string, unknown> = {}) {
  const r = await app.req('POST', '/api/stats/admin/meetings', { token: admin.token, body: { title: 'الاجتماع العام', location: 'مقر الجمعية', startsAt: app.now + 2 * H, ...extra } });
  expect(r.status).toBe(201);
  return r.body.id as string;
}

describe('statistics: access', () => {
  it('requires a session for every census route', async () => {
    for (const [m, p] of [['GET', '/api/stats/meetings'], ['GET', '/api/stats/me/registrations'], ['GET', '/api/stats/admin/dashboard'], ['POST', '/api/stats/admin/checkin']] as const) {
      expect((await app.req(m, p, { body: m === 'POST' ? {} : undefined })).status).toBe(401);
    }
  });

  it('keeps members out of the administration routes; ADMIN appoints census staff', async () => {
    const u = await app.register(1);
    expect((await app.req('GET', '/api/stats/access', { token: u.token })).body.level).toBeNull();
    expect((await app.req('GET', '/api/stats/admin/dashboard', { token: u.token })).status).toBe(403);
    expect((await app.req('POST', '/api/stats/admin/meetings', { token: u.token, body: { title: 'xxx', location: 'yy', startsAt: app.now + H } })).status).toBe(403);
    // An officer cannot appoint others; an admin can.
    expect((await app.req('POST', '/api/stats/admin/staff', { token: u.token, body: { identifier: 'user1@example.com', level: 'officer' } })).status).toBe(403);
    const g = await app.req('POST', '/api/stats/admin/staff', { token: admin.token, body: { identifier: u.phone, level: 'officer' } });
    expect(g.status).toBe(201);
    expect((await app.req('GET', '/api/stats/access', { token: u.token })).body.level).toBe('officer');
    expect((await app.req('GET', '/api/stats/admin/dashboard', { token: u.token })).status).toBe(200);
    // Officers register and confirm, but do not create meetings, correct data or export.
    expect((await app.req('POST', '/api/stats/admin/meetings', { token: u.token, body: { title: 'xxx', location: 'yy', startsAt: app.now + H } })).status).toBe(403);
    expect((await app.req('GET', '/api/stats/admin/registrations?all=1', { token: u.token })).status).toBe(403);
    expect((await app.req('DELETE', `/api/stats/admin/staff/${u.id}`, { token: admin.token })).status).toBe(200);
    expect((await app.req('GET', '/api/stats/admin/dashboard', { token: u.token })).status).toBe(403);
  });
});

describe('statistics: registration', () => {
  it('a member registers once, sees the card, and the admin sees the registration', async () => {
    const id = await meeting();
    const u = await app.register(2);
    const list = await app.req('GET', '/api/stats/meetings', { token: u.token });
    expect(list.body.items).toHaveLength(1);
    expect(list.body.items[0].registrationOpen).toBe(true);

    const r = await app.req('POST', `/api/stats/meetings/${id}/register`, { token: u.token, body: person });
    expect(r.status).toBe(201);
    expect(r.body.item).toMatchObject({ regNo: 1, status: 'registered', wilayaAr: 'وهران', firstName: 'أحمد' });
    // The QR code carries the registration id and a signature only — no personal data.
    expect(r.body.item.qr).toMatch(/^AST1\.[0-9a-f-]{36}\.[\w-]{27}$/);
    expect(r.body.item.qr).not.toContain('أحمد');

    const again = await app.req('POST', `/api/stats/meetings/${id}/register`, { token: u.token, body: person });
    expect(again.status).toBe(409);
    expect(again.body.error).toBe('already_registered');

    const adminList = await app.req('GET', `/api/stats/admin/registrations?meetingId=${id}`, { token: admin.token });
    expect(adminList.body.total).toBe(1);
    expect(adminList.body.items[0]).toMatchObject({ firstName: 'أحمد', hasAccount: true, source: 'member' });

    // The form is prefilled next time.
    const prof = await app.req('GET', '/api/stats/me/profile', { token: u.token });
    expect(prof.body.profile).toMatchObject({ firstName: 'أحمد', wilayaCode: 31, communeId: 1134, birthDate: '1995-04-12' });
  });

  it('rejects a commune outside the chosen wilaya, invalid dates and future birth dates', async () => {
    const id = await meeting();
    const u = await app.register(3);
    const bad = (b: Record<string, unknown>) => app.req('POST', `/api/stats/meetings/${id}/register`, { token: u.token, body: { ...person, ...b } });
    expect((await bad({ wilayaCode: 16 })).body.error).toBe('invalid_commune');
    expect((await bad({ birthDate: '1995-02-30' })).body.error).toBe('invalid_birth_date');
    expect((await bad({ birthDate: '2030-01-01' })).body.error).toBe('invalid_birth_date');
    expect((await bad({ maritalStatus: 'other' })).status).toBe(400);
    expect((await bad({ firstName: '' })).status).toBe(400);
  });

  it('the same person cannot be registered twice even with different spelling of hamza or case', async () => {
    const id = await meeting();
    const r1 = await app.req('POST', '/api/stats/admin/registrations', { token: admin.token, body: { ...person, firstName: 'أحمد', meetingId: id } });
    expect(r1.status).toBe(201);
    const r2 = await app.req('POST', '/api/stats/admin/registrations', { token: admin.token, body: { ...person, firstName: 'احمد', meetingId: id } });
    expect(r2.status).toBe(409);
    expect(personKey('Ahmed ', 'BEN ALI', '1990-01-01')).toBe(personKey('ahmed', 'ben  ali', '1990-01-01'));
  });

  it('numbers registrations per meeting and lets a member cancel and register again', async () => {
    const id = await meeting();
    const a = await app.register(4);
    const b = await app.register(5);
    const ra = await app.req('POST', `/api/stats/meetings/${id}/register`, { token: a.token, body: person });
    const rb = await app.req('POST', `/api/stats/meetings/${id}/register`, { token: b.token, body: { ...person, firstName: 'سارة', gender: 'female' } });
    expect([ra.body.item.regNo, rb.body.item.regNo]).toEqual([1, 2]);
    expect((await app.req('POST', `/api/stats/me/registrations/${ra.body.item.id}/cancel`, { token: a.token })).body.item.status).toBe('cancelled');
    const again = await app.req('POST', `/api/stats/meetings/${id}/register`, { token: a.token, body: person });
    expect(again.status).toBe(201);
    expect(again.body.item.regNo).toBe(1);
    // The old QR code no longer works after re-registration (new secret).
    expect(again.body.item.qr).not.toBe(ra.body.item.qr);
  });

  it('a member can never read another member’s registration', async () => {
    const id = await meeting();
    const a = await app.register(6);
    const b = await app.register(7);
    const ra = await app.req('POST', `/api/stats/meetings/${id}/register`, { token: a.token, body: person });
    expect((await app.req('GET', `/api/stats/me/registrations/${ra.body.item.id}`, { token: b.token })).status).toBe(404);
    expect((await app.req('POST', `/api/stats/me/registrations/${ra.body.item.id}/cancel`, { token: b.token })).status).toBe(404);
    expect((await app.req('GET', '/api/stats/me/registrations', { token: b.token })).body.items).toHaveLength(0);
  });

  it('refuses registration when closed by the organisers', async () => {
    const id = await meeting({ registrationOpen: false });
    const u = await app.register(8);
    const r = await app.req('POST', `/api/stats/meetings/${id}/register`, { token: u.token, body: person });
    expect(r.status).toBe(409);
    expect(r.body.error).toBe('registration_closed');
  });
});

describe('statistics: attendance', () => {
  it('QR check-in marks present once, updates the member side, and refuses a second scan', async () => {
    const id = await meeting();
    const u = await app.register(9);
    const reg = (await app.req('POST', `/api/stats/meetings/${id}/register`, { token: u.token, body: person })).body.item;
    const v1 = (await app.req('GET', '/api/stats/sync', { token: u.token })).body.version;

    // Before the confirmation window (opens 1 h before the start).
    let r = await app.req('POST', '/api/stats/admin/checkin', { token: admin.token, body: { meetingId: id, code: reg.qr } });
    expect(r.body.error).toBe('checkin_not_open');

    await advance(1.5 * H); // 30 min before the start
    r = await app.req('POST', '/api/stats/admin/checkin', { token: admin.token, body: { meetingId: id, code: reg.qr } });
    expect(r.status).toBe(200);
    expect(r.body.item).toMatchObject({ status: 'present', checkinMethod: 'qr' });
    expect(r.body.item.checkedInByName).toBeTruthy();

    const dup = await app.req('POST', '/api/stats/admin/checkin', { token: admin.token, body: { meetingId: id, code: reg.qr } });
    expect(dup.status).toBe(409);
    expect(dup.body.error).toBe('already_checked_in');

    const u2 = await app.req('POST', '/api/auth/refresh', { body: { refreshToken: u.refresh } });
    u.token = u2.body.tokens.accessToken;
    const mine = await app.req('GET', `/api/stats/me/registrations/${reg.id}`, { token: u.token });
    expect(mine.body.item.status).toBe('present');
    expect(mine.body.item.checkedInAt).toBe(app.now);
    expect((await app.req('GET', '/api/stats/sync', { token: u.token })).body.version).not.toBe(v1);
  });

  it('rejects forged and foreign QR codes', async () => {
    const id = await meeting();
    const other = await meeting({ title: 'اجتماع آخر' });
    const u = await app.register(10);
    const reg = (await app.req('POST', `/api/stats/meetings/${id}/register`, { token: u.token, body: person })).body.item;
    await advance(1.5 * H);
    const forged = reg.qr.slice(0, -3) + (reg.qr.endsWith('AAA') ? 'BBB' : 'AAA');
    expect((await app.req('POST', '/api/stats/admin/checkin', { token: admin.token, body: { meetingId: id, code: forged } })).body.error).toBe('invalid_qr');
    expect((await app.req('POST', '/api/stats/admin/checkin', { token: admin.token, body: { meetingId: id, code: 'hello' } })).body.error).toBe('invalid_qr');
    expect((await app.req('POST', '/api/stats/admin/checkin', { token: admin.token, body: { meetingId: other, code: reg.qr } })).body.error).toBe('wrong_meeting');
    // An ordinary member cannot confirm attendance.
    const fresh = (await app.req('POST', '/api/auth/refresh', { body: { refreshToken: u.refresh } })).body.tokens.accessToken;
    expect((await app.req('POST', '/api/stats/admin/checkin', { token: fresh, body: { meetingId: id, code: reg.qr } })).status).toBe(403);
  });

  it('late arrival, registration number and manual confirmation; closing marks the rest absent', async () => {
    const id = await meeting();
    const regs = [];
    for (let i = 0; i < 4; i++) {
      const r = await app.req('POST', '/api/stats/admin/registrations', {
        token: admin.token, body: { ...person, firstName: ['علي', 'مريم', 'يوسف', 'خديجة'][i], gender: i % 2 ? 'female' : 'male', birthDate: `${1970 + i * 10}-01-01`, meetingId: id },
      });
      regs.push(r.body.item);
    }
    await advance(2 * H + 30 * MIN); // 30 minutes after the start: late
    const byNo = await app.req('POST', '/api/stats/admin/checkin', { token: admin.token, body: { meetingId: id, regNo: regs[0].regNo } });
    expect(byNo.body.item).toMatchObject({ status: 'late', checkinMethod: 'number' });
    const manual = await app.req('POST', '/api/stats/admin/checkin', { token: admin.token, body: { meetingId: id, registrationId: regs[1].id } });
    expect(manual.body.item).toMatchObject({ status: 'late', checkinMethod: 'manual' });

    const dash = await app.req('GET', `/api/stats/admin/dashboard?meetingId=${id}`, { token: admin.token });
    expect(dash.body.stats.totals).toMatchObject({ registrations: 4, late: 2, registered: 2 });
    expect(dash.body.stats.gender).toMatchObject({ male: 2, female: 2 });
    expect(dash.body.stats.byWilaya[0]).toMatchObject({ code: 31, count: 4, attended: 2 });
    expect(dash.body.stats.attendanceRate).toBe(0.5);

    const closed = await app.req('POST', `/api/stats/admin/meetings/${id}/close`, { token: admin.token });
    expect(closed.body.report.totals).toMatchObject({ absent: 2, attended: 2, registered: 0 });
    expect((await app.req('POST', '/api/stats/admin/checkin', { token: admin.token, body: { meetingId: id, registrationId: regs[2].id } })).body.error).toBe('meeting_not_open');
  });

  it('managers correct records, and every correction is in the audit log', async () => {
    const id = await meeting();
    const r = (await app.req('POST', '/api/stats/admin/registrations', { token: admin.token, body: { ...person, meetingId: id } })).body.item;
    const fix = await app.req('PATCH', `/api/stats/admin/registrations/${r.id}`, { token: admin.token, body: { lastName: 'بن عيسى', status: 'present' } });
    expect(fix.status).toBe(200);
    expect(fix.body.item).toMatchObject({ lastName: 'بن عيسى', status: 'present', checkinMethod: 'manual' });
    const audit = await app.req('GET', '/api/stats/admin/audit', { token: admin.token });
    expect(audit.body.items.map((a: { action: string }) => a.action)).toEqual(expect.arrayContaining(['stats.registration.update', 'stats.registration.create', 'stats.meeting.create']));
  });

  it('filters and searches the records', async () => {
    const id = await meeting();
    await app.req('POST', '/api/stats/admin/registrations', { token: admin.token, body: { ...person, meetingId: id } });
    await app.req('POST', '/api/stats/admin/registrations', { token: admin.token, body: { ...person, firstName: 'كريم', lastName: 'حداد', wilayaCode: 16, communeId: 562, meetingId: id } });
    const q = (s: string) => app.req('GET', `/api/stats/admin/registrations?${s}`, { token: admin.token }).then((r) => r.body.total);
    expect(await q('q=حداد')).toBe(1);
    expect(await q('q=2')).toBe(1);
    expect(await q('wilaya=31')).toBe(1);
    expect(await q('status=registered')).toBe(2);
    expect(await q('q=%25')).toBe(0);
  });
});

describe('statistics: helpers', () => {
  it('computes ages and validates birth dates', () => {
    expect(ageOn('2000-06-15', Date.UTC(2026, 5, 14))).toBe(25);
    expect(ageOn('2000-06-15', Date.UTC(2026, 5, 15))).toBe(26);
    expect(validBirthDate('2024-02-29', Date.UTC(2026, 0, 1))).toBe(true);
    expect(validBirthDate('2023-02-29', Date.UTC(2026, 0, 1))).toBe(false);
  });
});
