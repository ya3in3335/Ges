#!/usr/bin/env node
// End-to-end check of every API feature against a running Worker (default: local `wrangler dev`).
// It creates test accounts, polls, news… so run it against a local or staging database only:
//   npx wrangler dev --local --var JWT_SECRET:... --var TELEGRAM_WEBHOOK_SECRET:... --var TELEGRAM_APPROVER_IDS:777000
//   BASE=http://127.0.0.1:8787 WEBHOOK_SECRET=... APPROVER=777000 node scripts/e2e.mjs
const BASE = process.env.BASE ?? 'http://127.0.0.1:8787';
const WEBHOOK_SECRET = process.env.WEBHOOK_SECRET ?? '';
const APPROVER = Number(process.env.APPROVER ?? 777000);
if (/workers\.dev|pages\.dev/.test(BASE) && !process.env.ALLOW_REMOTE) {
  console.error('Refusing to write test data to a deployed API (set ALLOW_REMOTE=1 to override).');
  process.exit(2);
}

let pass = 0, fail = 0, section = '';
const failures = [];
const log = (ok, name, extra = '') => {
  ok ? pass++ : fail++;
  if (!ok) failures.push(`${section} › ${name} ${extra}`);
  console.log(`${ok ? '  ✅' : '  ❌'} ${name}${extra ? ` — ${extra}` : ''}`);
};
const group = (s) => { section = s; console.log(`\n■ ${s}`); };

let ipSeq = 1;
async function api(method, path, { token, body, headers = {}, ip } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      'content-type': 'application/json',
      'cf-connecting-ip': ip ?? `10.0.0.${ipSeq}`,
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let data = null;
  const text = await res.text();
  try { data = JSON.parse(text); } catch { data = text; }
  return { status: res.status, data };
}
const expect = (name, r, status, check = () => true) => {
  const ok = r.status === status && check(r.data);
  log(ok, name, ok ? '' : `got ${r.status} ${JSON.stringify(r.data).slice(0, 160)}`);
  return r.data;
};

let updateId = Math.floor(Date.now() / 1000);
const webhook = (update, secret = WEBHOOK_SECRET) =>
  api('POST', '/telegram/webhook', { body: { update_id: ++updateId, ...update }, headers: { 'x-telegram-bot-api-secret-token': secret } });
const tgMsg = (tgId, extra) => ({ message: { message_id: updateId, date: Math.floor(Date.now() / 1000), from: { id: tgId, is_bot: false, first_name: 'Test' }, chat: { id: tgId, type: 'private' }, ...extra } });

const now = Date.now();
const H = 3600_000;
const stamp = String(now).slice(-6);
const users = [
  { name: 'مستخدم أول', email: `e2e1-${stamp}@example.com`, phone: `0555${stamp}`.slice(0, 10), tg: 5001, ip: '10.0.1.1' },
  { name: 'مستخدم ثاني', email: `e2e2-${stamp}@example.com`, phone: `0666${stamp}`.slice(0, 10), tg: 5002, ip: '10.0.1.2' },
  { name: 'مستخدم ثالث', email: `e2e3-${stamp}@example.com`, phone: `0777${stamp}`.slice(0, 10), tg: 5003, ip: '10.0.1.3' },
];
const intl = (p) => `213${p.slice(1)}`;
const PASSWORD = 'Test-Password-2026!';

// ───────────────────────────────────────────────────────────────
group('المحتوى العام (بدون تسجيل)');
expect('health', await api('GET', '/api/health'), 200, (d) => d.ok === true);
for (const p of ['/api/home', '/api/settings', '/api/donations', '/api/news', '/api/activities', '/api/media', '/api/polls']) {
  expect(`GET ${p}`, await api('GET', p), 200);
}
expect('مسار غير موجود → 404', await api('GET', '/api/nope'), 404);
expect('طريقة غير مسموحة → 405', await api('DELETE', '/api/health'), 405);

// ───────────────────────────────────────────────────────────────
group('تفعيل تطبيق المشرفين عبر البوت');
const dr = expect('طلب تفعيل جهاز', await api('POST', '/api/auth/device-requests', { body: { name: 'مشرف تجريبي', deviceName: 'E2E' }, ip: '10.0.2.1' }), 201, (d) => d.requestId && d.secret);
expect('الحالة قبل القرار = pending', await api('GET', `/api/auth/device-requests/${dr.requestId}`, { headers: { 'x-request-secret': dr.secret } }), 200, (d) => d.status === 'pending');
expect('سر خاطئ مرفوض', await api('GET', `/api/auth/device-requests/${dr.requestId}`, { headers: { 'x-request-secret': 'x'.repeat(30) } }), 404);
await webhook({ callback_query: { id: 'cq1', from: { id: 999 }, data: `dr:${dr.requestId}:s` } });
expect('شخص غير مخوّل لا يستطيع القبول', await api('GET', `/api/auth/device-requests/${dr.requestId}`, { headers: { 'x-request-secret': dr.secret } }), 200, (d) => d.status === 'pending');
await webhook({ callback_query: { id: 'cq2', from: { id: APPROVER }, data: `dr:${dr.requestId}:s` } });
const approved = expect('قبول المسؤول → جلسة مشرف عام', await api('GET', `/api/auth/device-requests/${dr.requestId}`, { headers: { 'x-request-secret': dr.secret } }), 200, (d) => d.status === 'approved' && d.user.role === 'SUPER_ADMIN' && d.tokens?.accessToken);
const admin = approved.tokens.accessToken;
expect('لا يمكن استلام نفس الطلب مرتين', await api('GET', `/api/auth/device-requests/${dr.requestId}`, { headers: { 'x-request-secret': dr.secret } }), 200, (d) => d.status === 'claimed');
const dr2 = (await api('POST', '/api/auth/device-requests', { body: { name: 'مرفوض' }, ip: '10.0.2.2' })).data;
await webhook({ callback_query: { id: 'cq3', from: { id: APPROVER }, data: `dr:${dr2.requestId}:r` } });
expect('رفض الطلب', await api('GET', `/api/auth/device-requests/${dr2.requestId}`, { headers: { 'x-request-secret': dr2.secret } }), 200, (d) => d.status === 'rejected');

group('رموز التفعيل والأجهزة');
const code = expect('إنشاء رمز تفعيل لمحرر', await api('POST', '/api/admin/activation-codes', { token: admin, body: { name: 'محرر تجريبي', role: 'EDITOR' } }), 201, (d) => /^\d{8}$/.test(d.code));
const ed = expect('تفعيل الجهاز بالرمز', await api('POST', '/api/auth/activate', { body: { code: code.code, deviceName: 'هاتف المحرر' }, ip: '10.0.2.3' }), 201, (d) => d.user.role === 'EDITOR');
expect('الرمز يستعمل مرة واحدة فقط', await api('POST', '/api/auth/activate', { body: { code: code.code }, ip: '10.0.2.3' }), 400);
const devs = expect('قائمة الأجهزة', await api('GET', '/api/admin/devices', { token: admin }), 200, (d) => d.items.length >= 2);
const edDev = devs.items.find((d) => d.device_name === 'هاتف المحرر' || d.deviceName === 'هاتف المحرر');
if (edDev) {
  expect('إلغاء جهاز', await api('DELETE', `/api/admin/devices/${edDev.id}`, { token: admin }), 200);
  expect('الجهاز الملغى لا يعمل بعدها', await api('GET', '/api/admin/news', { token: ed.tokens.accessToken }), 401);
} else log(false, 'إيجاد جهاز المحرر في القائمة', JSON.stringify(devs.items[0]));

// ───────────────────────────────────────────────────────────────
group('التسجيل والدخول');
expect('رقم هاتف غير صالح مرفوض', await api('POST', '/api/auth/register', { body: { fullName: 'اختبار', email: `bad-${stamp}@example.com`, phone: '12345', password: PASSWORD, confirmPassword: PASSWORD }, ip: '10.0.3.9' }), 400);
expect('كلمتا السر غير متطابقتين', await api('POST', '/api/auth/register', { body: { fullName: 'اختبار', email: `bad2-${stamp}@example.com`, phone: '0555999888', password: PASSWORD, confirmPassword: PASSWORD + 'x' }, ip: '10.0.3.9' }), 400);
for (const u of users) {
  const r = expect(`تسجيل ${u.name}`, await api('POST', '/api/auth/register', { body: { fullName: u.name, email: u.email, phone: u.phone, password: PASSWORD, confirmPassword: PASSWORD, locale: 'ar' }, ip: u.ip }), 201, (d) => d.tokens?.accessToken);
  u.token = r.tokens?.accessToken; u.refresh = r.tokens?.refreshToken; u.id = r.user?.id;
}
expect('نفس الهاتف لا يسجل مرتين', await api('POST', '/api/auth/register', { body: { fullName: 'مكرر', email: `dup-${stamp}@example.com`, phone: users[0].phone, password: PASSWORD, confirmPassword: PASSWORD }, ip: '10.0.3.8' }), 409);
expect('دخول بالهاتف', await api('POST', '/api/auth/login', { body: { identifier: users[0].phone, password: PASSWORD }, ip: '10.0.3.1' }), 200, (d) => d.tokens.accessToken);
expect('دخول بالبريد', await api('POST', '/api/auth/login', { body: { identifier: users[0].email, password: PASSWORD }, ip: '10.0.3.1' }), 200);
expect('كلمة سر خاطئة', await api('POST', '/api/auth/login', { body: { identifier: users[0].email, password: 'wrong-password' }, ip: '10.0.3.1' }), 401);
const rot = expect('تجديد الجلسة (refresh)', await api('POST', '/api/auth/refresh', { body: { refreshToken: users[0].refresh } }), 200, (d) => d.tokens.refreshToken !== users[0].refresh);
expect('إعادة استعمال refresh قديم تُكتشف', await api('POST', '/api/auth/refresh', { body: { refreshToken: users[0].refresh } }), 401);
expect('…وتُبطل العائلة كلها', await api('POST', '/api/auth/refresh', { body: { refreshToken: rot.tokens.refreshToken } }), 401);
const relog = (await api('POST', '/api/auth/login', { body: { identifier: users[0].email, password: PASSWORD }, ip: '10.0.3.2' })).data;
users[0].token = relog.tokens.accessToken; users[0].refresh = relog.tokens.refreshToken;
expect('GET /api/me', await api('GET', '/api/me', { token: users[0].token }), 200, (d) => d.user.email === users[0].email);
expect('تعديل الملف الشخصي', await api('PATCH', '/api/me', { token: users[0].token, body: { locale: 'fr' } }), 200);
expect('/api/me بدون تسجيل → 401', await api('GET', '/api/me'), 401);

// ───────────────────────────────────────────────────────────────
group('التحقق من الهاتف عبر تيليغرام');
expect('webhook بسر خاطئ مرفوض', await webhook(tgMsg(users[0].tg, { text: '/start x' }), 'wrong-secret'), 403);
for (const [i, u] of users.slice(0, 2).entries()) {
  const v = expect(`بدء التحقق (${u.name})`, await api('POST', '/api/verification/telegram', { token: u.token }), 201, (d) => d.deepLink?.includes('start='));
  const tok = new URL(v.deepLink).searchParams.get('start');
  await webhook(tgMsg(u.tg, { text: `/start ${tok}` }));
  expect('بعد /start → بانتظار جهة الاتصال', await api('GET', `/api/verification/telegram/${v.verificationId}`, { token: u.token }), 200, (d) => d.status === 'awaiting_contact');
  if (i === 0) {
    await webhook(tgMsg(u.tg, { contact: { phone_number: '+213555000999', user_id: 4242 } }));
    expect('جهة اتصال شخص آخر مرفوضة', await api('GET', `/api/verification/telegram/${v.verificationId}`, { token: u.token }), 200, (d) => d.status === 'awaiting_contact');
    await webhook(tgMsg(u.tg, { contact: { phone_number: '+213555000999', user_id: u.tg } }));
    expect('رقم مختلف عن رقم الحساب مرفوض', await api('GET', `/api/verification/telegram/${v.verificationId}`, { token: u.token }), 200, (d) => d.status === 'awaiting_contact');
  }
  await webhook(tgMsg(u.tg, { contact: { phone_number: `+${intl(u.phone)}`, user_id: u.tg } }));
  expect('مشاركة الرقم الصحيح → تم التحقق', await api('GET', `/api/verification/telegram/${v.verificationId}`, { token: u.token }), 200, (d) => d.status === 'verified');
}
expect('الحساب يظهر كموثّق', await api('GET', '/api/me', { token: users[0].token }), 200, (d) => !!d.user.phoneVerified || !!d.user.phoneVerifiedAt);
const other = (await api('POST', '/api/verification/telegram', { token: users[1].token })).data;
expect('رابط مستخدم لا يقرأه مستخدم آخر', await api('GET', `/api/verification/telegram/${other.verificationId ?? 'x'}`, { token: users[0].token }), 404);

// ───────────────────────────────────────────────────────────────
group('التصويت');
const pollBody = (o = {}) => ({
  titleAr: 'تصويت تجريبي', descriptionAr: 'اختبار', startsAt: Date.now() - 60_000, endsAt: Date.now() + 24 * H,
  resultVisibility: 'live', status: 'published', options: [{ labelAr: 'الخيار أ' }, { labelAr: 'الخيار ب' }], ...o,
});
expect('مستخدم عادي لا ينشئ تصويتاً', await api('POST', '/api/admin/polls', { token: users[0].token, body: pollBody() }), 403);
expect('تاريخ نهاية قبل البداية مرفوض', await api('POST', '/api/admin/polls', { token: admin, body: pollBody({ endsAt: Date.now() - 2 * H }) }), 400);
const poll = expect('المشرف ينشئ تصويتاً', await api('POST', '/api/admin/polls', { token: admin, body: pollBody() }), 201);
const pd = expect('تفاصيل التصويت (موثّق → مؤهل)', await api('GET', `/api/polls/${poll.id}`, { token: users[0].token }), 200, (d) => d.eligible === true && d.options.length === 2);
const [optA, optB] = pd.options.map((o) => o.id);
expect('غير موثّق → غير مؤهل', await api('GET', `/api/polls/${poll.id}`, { token: users[2].token }), 200, (d) => d.eligible === false && d.ineligibleReason === 'phone_not_verified');
expect('غير الموثّق لا يصوّت', await api('POST', `/api/polls/${poll.id}/vote`, { token: users[2].token, body: { optionId: optA } }), 403);
expect('بدون تسجيل لا يصوّت', await api('POST', `/api/polls/${poll.id}/vote`, { body: { optionId: optA } }), 401);
expect('خيار غير موجود مرفوض', await api('POST', `/api/polls/${poll.id}/vote`, { token: users[0].token, body: { optionId: crypto.randomUUID() } }), 400);
expect('تصويت المستخدم الأول', await api('POST', `/api/polls/${poll.id}/vote`, { token: users[0].token, body: { optionId: optA } }), 201, (d) => d.receipt);
expect('لا يمكن التصويت مرتين', await api('POST', `/api/polls/${poll.id}/vote`, { token: users[0].token, body: { optionId: optB } }), 409);
expect('تصويت المستخدم الثاني', await api('POST', `/api/polls/${poll.id}/vote`, { token: users[1].token, body: { optionId: optB } }), 201);
expect('النتائج المباشرة صحيحة', await api('GET', `/api/polls/${poll.id}`, { token: users[0].token }), 200,
  (d) => d.hasVoted && d.results.totalVotes === 2 && d.results.byOption[optA] === 1 && d.results.byOption[optB] === 1);
expect('قائمة التصويتات تُظهر أنه صوّت', await api('GET', '/api/polls', { token: users[0].token }), 200, (d) => d.items.some((p) => p.id === poll.id && p.hasVoted));
expect('نتائج المشرف', await api('GET', `/api/admin/polls/${poll.id}/results`, { token: admin }), 200);
expect('قواعد التصويت مقفلة بعد بدء التصويت', await api('PATCH', `/api/admin/polls/${poll.id}`, { token: admin, body: { eligibility: 'members' } }), 409);
expect('إغلاق التصويت مبكراً', await api('PATCH', `/api/admin/polls/${poll.id}`, { token: admin, body: { endsAt: Date.now() - 1000 } }), 200);
expect('تصويت بعد الإغلاق مرفوض', await api('POST', `/api/polls/${poll.id}/vote`, { token: users[1].token, body: { optionId: optA } }), 409);

const mpoll = expect('تصويت خاص بالأعضاء', await api('POST', '/api/admin/polls', { token: admin, body: pollBody({ eligibility: 'members', resultVisibility: 'after_close', titleAr: 'تصويت الأعضاء' }) }), 201);
expect('غير العضو غير مؤهل', await api('GET', `/api/polls/${mpoll.id}`, { token: users[1].token }), 200, (d) => d.eligible === false);
expect('إضافة عضو بالهاتف', await api('POST', `/api/admin/polls/${mpoll.id}/members`, { token: admin, body: { identifiers: [users[1].phone] } }), 200, (d) => d.added === 1);
const mp = expect('العضو صار مؤهلاً', await api('GET', `/api/polls/${mpoll.id}`, { token: users[1].token }), 200, (d) => d.eligible === true && d.results === null);
expect('العضو يصوّت', await api('POST', `/api/polls/${mpoll.id}/vote`, { token: users[1].token, body: { optionId: mp.options[0].id } }), 201);
expect('النتائج مخفية حتى الإغلاق', await api('GET', `/api/polls/${mpoll.id}`, { token: users[1].token }), 200, (d) => d.results === null);
expect('إزالة عضو', await api('DELETE', `/api/admin/polls/${mpoll.id}/members/${users[0].id}`, { token: admin }), 200);
const draft = (await api('POST', '/api/admin/polls', { token: admin, body: pollBody({ status: 'draft', titleAr: 'مسودة تصويت' }) })).data;
expect('المسودة لا تظهر للأعضاء', await api('GET', `/api/polls/${draft.id}`, { token: users[0].token }), 404);
expect('قائمة تصويتات المشرف', await api('GET', '/api/admin/polls', { token: admin }), 200, (d) => d.items.length >= 3);

// ───────────────────────────────────────────────────────────────
group('الأخبار');
const news = expect('إنشاء خبر منشور', await api('POST', '/api/admin/news', { token: admin, body: { titleAr: 'خبر تجريبي', bodyAr: 'نص الخبر', status: 'published', category: 'announcement' } }), 201);
expect('الخبر يظهر في القائمة', await api('GET', '/api/news'), 200, (d) => d.items.some((n) => n.id === news.id));
expect('تفاصيل الخبر', await api('GET', `/api/news/${news.id}`), 200);
expect('بحث في الأخبار', await api('GET', `/api/news?q=${encodeURIComponent('تجريبي')}`), 200, (d) => d.items.length >= 1);
expect('تعديل الخبر', await api('PATCH', `/api/admin/news/${news.id}`, { token: admin, body: { titleAr: 'خبر معدّل' } }), 200);
expect('الإعلان يظهر في الرئيسية', await api('GET', '/api/home'), 200, (d) => JSON.stringify(d).includes('خبر معدّل'));
expect('مستخدم عادي لا يعدّل الأخبار', await api('PATCH', `/api/admin/news/${news.id}`, { token: users[0].token, body: { titleAr: 'xxx' } }), 403);
expect('حذف الخبر', await api('DELETE', `/api/admin/news/${news.id}`, { token: admin }), 200);
expect('الخبر المحذوف لا يظهر', await api('GET', `/api/news/${news.id}`), 404);

// ───────────────────────────────────────────────────────────────
group('الأنشطة');
const act = expect('إنشاء نشاط بسعة 1', await api('POST', '/api/admin/activities', { token: admin, body: { titleAr: 'نشاط تجريبي', descriptionAr: 'وصف', location: 'وهران', startsAt: Date.now() + 48 * H, endsAt: Date.now() + 50 * H, capacity: 1, status: 'published' } }), 201);
expect('النشاط يظهر في القادمة', await api('GET', '/api/activities?phase=upcoming'), 200, (d) => d.items.some((a) => a.id === act.id));
expect('تسجيل المستخدم الأول', await api('POST', `/api/activities/${act.id}/register`, { token: users[0].token }), 201);
expect('النشاط ممتلئ للثاني', await api('POST', `/api/activities/${act.id}/register`, { token: users[1].token }), 409, (d) => d.error === 'activity_full');
expect('أنشطتي', await api('GET', '/api/me/activities', { token: users[0].token }), 200, (d) => d.items.some((a) => a.id === act.id));
const parts = expect('قائمة المشاركين', await api('GET', `/api/admin/activities/${act.id}/participants`, { token: admin }), 200, (d) => d.items.length === 1);
expect('إلغاء التسجيل', await api('POST', `/api/activities/${act.id}/cancel`, { token: users[0].token }), 200);
expect('بعد الإلغاء يمكن للثاني التسجيل', await api('POST', `/api/activities/${act.id}/register`, { token: users[1].token }), 201);
expect('تسجيل الحضور', await api('PATCH', `/api/admin/activities/${act.id}/participants/${users[1].id}`, { token: admin, body: { status: 'attended' } }), 200);
expect('تعديل النشاط', await api('PATCH', `/api/admin/activities/${act.id}`, { token: admin, body: { capacity: 10 } }), 200);
void parts;

// ───────────────────────────────────────────────────────────────
group('التطوع');
const vol = expect('طلب تطوع', await api('POST', '/api/volunteers', { token: users[0].token, body: { fullName: 'مستخدم أول', contact: users[0].phone, skills: 'تنظيم', availability: 'نهاية الأسبوع' } }), 201);
expect('طلب مكرر مرفوض', await api('POST', '/api/volunteers', { token: users[0].token, body: { fullName: 'مستخدم أول', contact: users[0].phone, skills: 'تنظيم', availability: 'نهاية الأسبوع' } }), 409);
expect('حالة طلبي', await api('GET', '/api/volunteers/me', { token: users[0].token }), 200);
const vl = expect('قائمة المتطوعين للمشرف', await api('GET', '/api/admin/volunteers', { token: admin }), 200, (d) => d.items.length >= 1);
const vid = vol?.id ?? vl.items[0].id;
expect('قبول المتطوع', await api('PATCH', `/api/admin/volunteers/${vid}`, { token: admin, body: { status: 'approved' } }), 200);
const relog2 = (await api('POST', '/api/auth/login', { body: { identifier: users[0].email, password: PASSWORD }, ip: '10.0.3.3' })).data;
users[0].token = relog2.tokens.accessToken;
expect('الدور صار متطوعاً', await api('GET', '/api/me', { token: users[0].token }), 200, (d) => d.user.role === 'VOLUNTEER');
expect('تعيين المتطوع لنشاط', await api('POST', `/api/admin/volunteers/${vid}/assignments`, { token: admin, body: { activityId: act.id } }), 200);
expect('إلغاء التعيين', await api('DELETE', `/api/admin/volunteers/${vid}/assignments/${act.id}`, { token: admin }), 200);
expect('سحب طلب التطوع', await api('DELETE', '/api/volunteers/me', { token: users[0].token }), 200);

// ───────────────────────────────────────────────────────────────
group('الوسائط والإعدادات والتبرعات');
const med = expect('إضافة وسيط خارجي', await api('POST', '/api/admin/media', { token: admin, body: { kind: 'video', url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ', titleAr: 'فيديو تجريبي' } }), 201);
expect('الوسيط يظهر', await api('GET', '/api/media'), 200, (d) => d.items.some((m) => m.id === med.id));
expect('تعديل الوسيط', await api('PATCH', `/api/admin/media/${med.id}`, { token: admin, body: { titleAr: 'معدّل' } }), 200);
expect('حذف الوسيط', await api('DELETE', `/api/admin/media/${med.id}`, { token: admin }), 200);
expect('رقم CCP غير صالح مرفوض', await api('PUT', '/api/admin/donations', { token: admin, body: { ccpAccount: 'abc' } }), 400);
expect('تحديث معلومات التبرع', await api('PUT', '/api/admin/donations', { token: admin, body: { ccpAccount: '1234567', ccpKey: '12', instructionsAr: 'نص تجريبي' } }), 200);
expect('معلومات التبرع العامة', await api('GET', '/api/donations'), 200, (d) => JSON.stringify(d).includes('1234567'));
expect('تحديث الإعدادات', await api('PUT', '/api/admin/settings', { token: admin, body: { about_ar: 'نص تجريبي' } }), 200);

// ───────────────────────────────────────────────────────────────
group('الإشعارات');
expect('تسجيل جهاز للإشعارات', await api('POST', '/api/devices', { token: users[0].token, body: { token: 'fcm-test-token-0123456789-abcdef', locale: 'ar' } }), 200);
expect('إرسال إشعار من المشرف', await api('POST', '/api/admin/notifications', { token: admin, body: { topic: 'important', titleAr: 'إشعار تجريبي', bodyAr: 'نص الإشعار' } }), 201);
expect('سجل إشعارات المشرف', await api('GET', '/api/admin/notifications', { token: admin }), 200, (d) => d.items.length >= 1);
expect('إشعاراتي', await api('GET', '/api/me/notifications', { token: users[0].token }), 200, (d) => d.items.length >= 1);
expect('إلغاء تسجيل الجهاز', await api('POST', '/api/devices/unregister', { body: { token: 'fcm-test-token-0123456789-abcdef' } }), 200);

// ───────────────────────────────────────────────────────────────
group('الإدارة والصلاحيات');
expect('مستخدم عادي ممنوع من لوحة الإدارة', await api('GET', '/api/admin/overview', { token: users[1].token }), 403);
expect('زائر ممنوع من لوحة الإدارة', await api('GET', '/api/admin/overview'), 401);
expect('نظرة عامة', await api('GET', '/api/admin/overview', { token: admin }), 200);
expect('إحصائيات تيليغرام', await api('GET', '/api/admin/telegram-stats', { token: admin }), 200);
expect('قائمة المستخدمين', await api('GET', '/api/admin/users', { token: admin }), 200, (d) => d.items.length >= 3);
expect('سجل التدقيق', await api('GET', '/api/admin/audit', { token: admin }), 200, (d) => d.items.length >= 1);
expect('إيقاف حساب', await api('PATCH', `/api/admin/users/${users[2].id}`, { token: admin, body: { status: 'suspended' } }), 200);
expect('الحساب الموقوف يُطرد فوراً', await api('GET', '/api/me', { token: users[2].token }), 401);
// Same answer as a wrong password, so suspended accounts cannot be discovered.
expect('الموقوف لا يدخل', await api('POST', '/api/auth/login', { body: { identifier: users[2].email, password: PASSWORD }, ip: '10.0.3.4' }), 401);
expect('إعادة تفعيل الحساب', await api('PATCH', `/api/admin/users/${users[2].id}`, { token: admin, body: { status: 'active' } }), 200);
expect('إعادة ضبط التحقق', await api('POST', `/api/admin/users/${users[1].id}/reset-verification`, { token: admin }), 200);
expect('المشرف لا يعدّل نفسه', await api('PATCH', `/api/admin/users/${approved.user.id}`, { token: admin, body: { status: 'suspended' } }), 403);

// ───────────────────────────────────────────────────────────────
group('كلمة السر والحساب');
const u3 = (await api('POST', '/api/auth/login', { body: { identifier: users[2].email, password: PASSWORD }, ip: '10.0.3.5' })).data;
expect('تغيير كلمة السر', await api('POST', '/api/me/password', { token: u3.tokens.accessToken, body: { currentPassword: PASSWORD, newPassword: PASSWORD + 'X' } }), 200);
expect('الدخول بكلمة السر الجديدة', await api('POST', '/api/auth/login', { body: { identifier: users[2].email, password: PASSWORD + 'X' }, ip: '10.0.3.5' }), 200);
expect('طلب استرجاع كلمة السر', await api('POST', '/api/auth/password/forgot', { body: { identifier: users[2].email }, ip: '10.0.3.6' }), 200);
expect('تسجيل الخروج', await api('POST', '/api/auth/logout', { token: users[1].token, body: { refreshToken: 'x'.repeat(40) } }), 200);
const u2 = (await api('POST', '/api/auth/login', { body: { identifier: users[1].email, password: PASSWORD }, ip: '10.0.3.7' })).data;
expect('حذف الحساب', await api('DELETE', '/api/me', { token: u2.tokens.accessToken, body: { password: PASSWORD } }), 200);
expect('الحساب المحذوف لا يدخل', await api('POST', '/api/auth/login', { body: { identifier: users[1].email, password: PASSWORD }, ip: '10.0.3.7' }), 401);

console.log(`\n══════ النتيجة: ${pass} نجح، ${fail} فشل ══════`);
if (failures.length) { console.log(failures.map((f) => ' - ' + f).join('\n')); process.exit(1); }
