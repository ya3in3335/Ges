#!/usr/bin/env node
// End-to-end check of the census (statistics) flow between the member app and the admin app, against a
// running Worker with a LOCAL database (`wrangler dev --local`). The admin role is granted with
// `wrangler d1 execute --local`, so this never touches production data.
//   npx wrangler dev --local --port 8788 &
//   BASE=http://127.0.0.1:8788 node scripts/e2e-stats.mjs
import { execFileSync } from 'node:child_process';

const BASE = process.env.BASE ?? 'http://127.0.0.1:8788';
if (!/127\.0\.0\.1|localhost/.test(BASE)) { console.error('Local Worker only.'); process.exit(2); }
let pass = 0, fail = 0;
const ok = (cond, name, extra = '') => { cond ? pass++ : fail++; console.log(`${cond ? '  ✅' : '  ❌'} ${name}${extra ? ` — ${extra}` : ''}`); };
let ip = 1;
async function api(method, path, { token, body } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'content-type': 'application/json', 'cf-connecting-ip': `10.7.0.${ip++ % 250}`, ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  return { status: res.status, data: text ? JSON.parse(text) : null };
}
const sql = (q) => execFileSync('npx', ['wrangler', 'd1', 'execute', 'association_db', '--local', '--command', q], { stdio: 'pipe' });
const run = Date.now().toString(36);
const account = async (n) => {
  const body = { fullName: `مستخدم تجريبي ${'ابجد'[n]}`, email: `stats-${run}-${n}@example.com`, phone: `0661${String(Date.now() + n).slice(-6)}`, password: 'Secret123x', confirmPassword: 'Secret123x' };
  const r = await api('POST', '/api/auth/register', { body });
  if (r.status !== 201) throw new Error(JSON.stringify(r));
  return { ...body, id: r.data.user.id };
};
// Same request as both apps' sign-in screen (client "android", the association's login endpoint).
const login = async (a) => (await api('POST', '/api/auth/login', { body: { identifier: a.email, password: a.password } })).data.tokens.accessToken;

console.log('\n■ Accounts (the association’s own accounts)');
const boss = await account(1);
const member = await account(2);
const officer = await account(3);
sql(`UPDATE users SET role = 'ADMIN' WHERE id = '${boss.id}'`);
const adminT = await login(boss);
const memberT = await login(member);
let officerT = await login(officer);
ok(!!adminT && !!memberT, 'members and admins sign in with the same login as the original apps');
ok((await api('GET', '/api/stats/meetings')).status === 401, 'nothing is readable without a session');
ok((await api('GET', '/api/stats/access', { token: memberT })).data.level === null, 'an ordinary member has no census administration access');
ok((await api('GET', '/api/stats/admin/dashboard', { token: memberT })).status === 403, 'the server refuses admin routes to a member');
ok((await api('GET', '/api/stats/access', { token: adminT })).data.level === 'manager', 'an ADMIN account is a census manager');

console.log('\n■ Meeting (admin app)');
const now = Date.now();
const m = await api('POST', '/api/stats/admin/meetings', { token: adminT, body: { title: 'اجتماع تجريبي', location: 'مقر الجمعية', startsAt: now + 30 * 60_000, checkinOpensAt: now - 60_000 } });
ok(m.status === 201, 'manager creates a meeting');
const meetingId = m.data.id;

console.log('\n■ Member app → backend → admin app');
const list = await api('GET', '/api/stats/meetings', { token: memberT });
ok(list.data.items.some((x) => x.id === meetingId && x.registrationOpen), 'the member sees the meeting open for registration');
const v0 = (await api('GET', '/api/stats/sync', { token: adminT })).data.version;
const person = { firstName: 'أحمد', lastName: 'بن علي', gender: 'male', wilayaCode: 31, communeId: 1134, birthDate: '1995-04-12', maritalStatus: 'married' };
const reg = await api('POST', `/api/stats/meetings/${meetingId}/register`, { token: memberT, body: person });
ok(reg.status === 201 && reg.data.item.regNo === 1, 'the member registers in the census', `#${reg.data.item?.regNo}`);
ok(!JSON.stringify(reg.data.item.qr).includes('أحمد'), 'the QR code holds no personal data');
ok((await api('POST', `/api/stats/meetings/${meetingId}/register`, { token: memberT, body: person })).status === 409, 'double registration is refused');
const v1 = (await api('GET', '/api/stats/sync', { token: adminT })).data.version;
ok(v1 !== v0, 'the admin app’s live counter changes right away');
const seen = await api('GET', `/api/stats/admin/registrations?meetingId=${meetingId}`, { token: adminT });
ok(seen.data.items.some((r) => r.id === reg.data.item.id && r.hasAccount), 'the admin app shows the new registration');
const prof = await api('GET', '/api/stats/me/profile', { token: memberT });
ok(prof.data.profile.communeId === 1134, 'the member’s next form is prefilled');

console.log('\n■ Officer registers a walk-in, then attendance (admin app → backend → member app)');
ok((await api('POST', '/api/stats/admin/staff', { token: adminT, body: { identifier: officer.email, level: 'officer' } })).status === 201, 'the admin appoints a census officer');
officerT = await login(officer);
const walk = await api('POST', '/api/stats/admin/registrations', { token: officerT, body: { ...person, firstName: 'مريم', gender: 'female', meetingId } });
ok(walk.status === 201 && walk.data.item.regNo === 2, 'the officer registers a person without an account');
const mv0 = (await api('GET', '/api/stats/sync', { token: memberT })).data.version;
const scan = await api('POST', '/api/stats/admin/checkin', { token: officerT, body: { meetingId, code: reg.data.item.qr } });
ok(scan.status === 200 && scan.data.item.status === 'present', 'scanning the member’s QR confirms attendance', scan.data.item?.checkedInByName);
ok((await api('POST', '/api/stats/admin/checkin', { token: officerT, body: { meetingId, code: reg.data.item.qr } })).data.error === 'already_checked_in', 'a second scan is refused');
const card = await api('GET', `/api/stats/me/registrations/${reg.data.item.id}`, { token: memberT });
ok(card.data.item.status === 'present' && card.data.item.checkedInAt, 'the member’s card now says present, with the time');
ok((await api('GET', '/api/stats/sync', { token: memberT })).data.version !== mv0, 'the member app’s live counter changed');
const byNo = await api('POST', '/api/stats/admin/checkin', { token: officerT, body: { meetingId, regNo: 2 } });
ok(byNo.data.item?.status === 'present', 'confirmation by registration number');
ok((await api('GET', `/api/stats/me/registrations/${walk.data.item.id}`, { token: memberT })).status === 404, 'a member cannot read someone else’s registration');
ok((await api('GET', '/api/stats/admin/registrations?all=1', { token: officerT })).status === 403, 'officers cannot export personal data');

console.log('\n■ Dashboard, export, closing, audit');
const d = await api('GET', `/api/stats/admin/dashboard?meetingId=${meetingId}`, { token: adminT });
ok(d.data.stats.totals.attended === 2 && d.data.stats.gender.female === 1, 'dashboard numbers', JSON.stringify(d.data.stats.totals));
ok((await api('GET', `/api/stats/admin/registrations?all=1&meetingId=${meetingId}`, { token: adminT })).data.items.length === 2, 'manager export gets the full list');
const closed = await api('POST', `/api/stats/admin/meetings/${meetingId}/close`, { token: adminT });
ok(closed.status === 200 && closed.data.report.totals.attended === 2, 'closing produces the final report');
const audit = await api('GET', '/api/stats/admin/audit', { token: adminT });
ok(['stats.meeting.create', 'stats.staff.grant', 'stats.export', 'stats.meeting.close'].every((a) => audit.data.items.some((x) => x.action === a)), 'administrative actions are in the audit log');

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
