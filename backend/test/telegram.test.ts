import { beforeEach, describe, expect, it } from 'vitest';
import { TestApp, tgUser } from './helpers';

let app: TestApp;
beforeEach(() => { app = new TestApp(); });

async function start(token: string) {
  const r = await app.req('POST', '/api/verification/telegram', { token });
  expect(r.status).toBe(201);
  const tgToken = new URL(r.body.deepLink).searchParams.get('start')!;
  return { id: r.body.verificationId as string, tgToken, body: r.body };
}
const status = async (id: string, token: string) => (await app.req('GET', `/api/verification/telegram/${id}`, { token })).body;
const lastReply = () => app.telegram.filter((t) => t.method === 'sendMessage' && t.body.chat_id !== '-100123').at(-1)!.body;
const contact = (tgId: number, phone: string, contactUserId: number | null = tgId, extra: Record<string, unknown> = {}) =>
  app.tg({ ...tgUser(tgId), contact: { phone_number: phone, ...(contactUserId === null ? {} : { user_id: contactUserId }), first_name: 'X' }, ...extra });

describe('Telegram phone verification', () => {
  it('verifies a matching shared contact end-to-end and notifies the admin channel without PII', async () => {
    const u = await app.register(1); // +213550100001
    const s = await start(u.token);
    expect(s.tgToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(s.body.deepLink).toBe(`https://t.me/TestAssocBot?start=${s.tgToken}`);
    // only the hash of the token is stored
    expect(JSON.stringify(app.db.raw.prepare('SELECT * FROM telegram_verifications').all())).not.toContain(s.tgToken);

    await app.tg({ ...tgUser(555), text: `/start ${s.tgToken}` });
    expect(lastReply().reply_markup.keyboard[0][0].request_contact).toBe(true);
    expect((await status(s.id, u.token)).status).toBe('awaiting_contact');

    await contact(555, '213550100001');
    expect(lastReply().text).toContain('تم تأكيد رقم هاتفك بنجاح');
    const st = await status(s.id, u.token);
    expect(st.status).toBe('verified');
    const me = await app.req('GET', '/api/me', { token: u.token });
    expect(me.body.user.phoneVerified).toBe(true);
    expect(me.body.user.telegramLinked).toBe(true);

    const channelMsg = app.telegram.find((t) => t.body.chat_id === '-100123');
    expect(channelMsg).toBeDefined();
    expect(channelMsg!.body.text).toContain('✅ تم توثيق حساب جديد');
    expect(channelMsg!.body.text).toContain(u.id);
    expect(channelMsg!.body.text).toContain('إجمالي المستخدمين المؤكدين: 1');
    expect(channelMsg!.body.text).not.toContain('550100001');
    expect(channelMsg!.body.text).not.toContain(s.tgToken);
    expect((app.db.raw.prepare(`SELECT status FROM channel_outbox`).get() as { status: string }).status).toBe('sent');
  });

  it('rejects a contact that belongs to another Telegram user', async () => {
    const u = await app.register(1);
    const s = await start(u.token);
    await app.tg({ ...tgUser(555), text: `/start ${s.tgToken}` });
    await contact(555, '213550100001', 999);
    expect(lastReply().text).toContain('لا يمكن قبول جهة اتصال تخص شخصاً آخر');
    expect((await status(s.id, u.token)).failureReason).toBe('foreign_contact');
    expect((await app.req('GET', '/api/me', { token: u.token })).body.user.phoneVerified).toBe(false);
  });

  it('rejects contacts without a user_id (e.g. a typed or address-book contact)', async () => {
    const u = await app.register(1);
    const s = await start(u.token);
    await app.tg({ ...tgUser(555), text: `/start ${s.tgToken}` });
    await contact(555, '213550100001', null);
    expect((await status(s.id, u.token)).status).toBe('awaiting_contact');
    expect((await app.req('GET', '/api/me', { token: u.token })).body.user.phoneVerified).toBe(false);
  });

  it('rejects forwarded contacts', async () => {
    const u = await app.register(1);
    const s = await start(u.token);
    await app.tg({ ...tgUser(555), text: `/start ${s.tgToken}` });
    await contact(555, '213550100001', 555, { forward_origin: { type: 'user' } });
    expect((await status(s.id, u.token)).failureReason).toBe('forwarded_contact');
  });

  it('never accepts manually typed phone numbers', async () => {
    const u = await app.register(1);
    const s = await start(u.token);
    await app.tg({ ...tgUser(555), text: `/start ${s.tgToken}` });
    await app.tg({ ...tgUser(555), text: '+213550100001' });
    expect(lastReply().text).toContain('لا يمكن قبول الأرقام المكتوبة يدوياً');
    expect((await status(s.id, u.token)).status).toBe('awaiting_contact');
    const audit = app.db.raw.prepare(`SELECT event FROM verification_audit WHERE event = 'typed_number_rejected'`).all();
    expect(audit).toHaveLength(1);
  });

  it('reports a phone mismatch and fails after 3 attempts', async () => {
    const u = await app.register(1);
    const s = await start(u.token);
    await app.tg({ ...tgUser(555), text: `/start ${s.tgToken}` });
    await contact(555, '213661000000');
    expect(lastReply().text).toBe('الرقم لا يطابق الرقم المسجل. يرجى مراجعته والمحاولة مجدداً.');
    const st = await status(s.id, u.token);
    expect(st.failureReason).toBe('phone_mismatch');
    expect(st.attemptsLeft).toBe(2);
    await contact(555, '213661000000');
    await contact(555, '213661000000');
    expect((await status(s.id, u.token)).status).toBe('failed');
    // even the right number is refused once the session failed
    await contact(555, '213550100001');
    expect((await app.req('GET', '/api/me', { token: u.token })).body.user.phoneVerified).toBe(false);
    // audit never stores the full shared number
    expect(JSON.stringify(app.db.raw.prepare('SELECT detail FROM verification_audit').all())).not.toContain('661000000');
  });

  it('rejects expired sessions', async () => {
    const u = await app.register(1);
    const s = await start(u.token);
    app.now += 11 * 60_000;
    await app.tg({ ...tgUser(555), text: `/start ${s.tgToken}` });
    expect(lastReply().text).toContain('انتهت صلاحية');
    expect((await status(s.id, u.token)).status).toBe('expired');
  });

  it('rejects a session that expires between /start and the contact', async () => {
    const u = await app.register(1);
    const s = await start(u.token);
    await app.tg({ ...tgUser(555), text: `/start ${s.tgToken}` });
    app.now += 11 * 60_000;
    await contact(555, '213550100001');
    expect((await app.req('GET', '/api/me', { token: u.token })).body.user.phoneVerified).toBe(false);
  });

  it('prevents token reuse after success (replay)', async () => {
    const u = await app.register(1);
    const s = await start(u.token);
    await app.tg({ ...tgUser(555), text: `/start ${s.tgToken}` });
    await contact(555, '213550100001');
    await app.tg({ ...tgUser(555), text: `/start ${s.tgToken}` });
    expect(lastReply().text).toContain('تمت معالجة هذا الطلب مسبقاً');
    await app.tg({ ...tgUser(666), text: `/start ${s.tgToken}` });
    expect(app.db.raw.prepare('SELECT COUNT(*) AS n FROM telegram_account_links').get()).toEqual({ n: 1 });
    expect(app.db.raw.prepare('SELECT COUNT(*) AS n FROM channel_outbox').get()).toEqual({ n: 1 });
  });

  it('binds the token to the first Telegram account that opens it', async () => {
    const u = await app.register(1);
    const s = await start(u.token);
    await app.tg({ ...tgUser(555), text: `/start ${s.tgToken}` });
    await app.tg({ ...tgUser(666), text: `/start ${s.tgToken}` });
    expect(lastReply().text).toContain('مرتبط بحساب تيليغرام آخر');
    // The other account cannot complete verification even with the right number
    await contact(666, '213550100001');
    expect((await app.req('GET', '/api/me', { token: u.token })).body.user.phoneVerified).toBe(false);
  });

  it('ignores duplicate Telegram updates (same update_id)', async () => {
    const u = await app.register(1);
    const s = await start(u.token);
    await app.tg({ ...tgUser(555), text: `/start ${s.tgToken}` }, { updateId: 42 });
    const before = app.telegram.length;
    const dup = await app.tg({ ...tgUser(555), text: `/start ${s.tgToken}` }, { updateId: 42 });
    expect(dup.body.duplicate).toBe(true);
    expect(app.telegram.length).toBe(before);
  });

  it('rejects webhook calls without the secret token', async () => {
    const r = await app.tg({ ...tgUser(555), text: '/start x' }, { secret: 'wrong' });
    expect(r.status).toBe(403);
    const r2 = await app.req('POST', '/telegram/webhook', { body: { update_id: 1 } });
    expect(r2.status).toBe(403);
  });

  it('refuses to link one Telegram account to two app accounts', async () => {
    const a = await app.register(1);
    const sa = await start(a.token);
    await app.tg({ ...tgUser(555), text: `/start ${sa.tgToken}` });
    await contact(555, '213550100001');
    // A second app account (different phone) tries with the same Telegram account
    const b = await app.register(2);
    const sb = await start(b.token);
    await app.tg({ ...tgUser(555), text: `/start ${sb.tgToken}` });
    await contact(555, '213550100002');
    expect((await app.req('GET', '/api/me', { token: b.token })).body.user.phoneVerified).toBe(false);
    const st = await status(sb.id, b.token);
    expect(st.status).toBe('failed');
    expect(st.failureReason).toBe('telegram_already_linked');
  });

  it('protects verification status from other users (IDOR)', async () => {
    const a = await app.register(1);
    const b = await app.register(2);
    const s = await start(a.token);
    expect((await app.req('GET', `/api/verification/telegram/${s.id}`, { token: b.token })).status).toBe(404);
    expect((await app.req('GET', `/api/verification/telegram/${s.id}`)).status).toBe(401);
  });

  it('cancels older sessions when a new one starts', async () => {
    const u = await app.register(1);
    const s1 = await start(u.token);
    await start(u.token);
    await app.tg({ ...tgUser(555), text: `/start ${s1.tgToken}` });
    expect(lastReply().text).toContain('انتهت صلاحية');
  });

  it('does not start a session for an already verified account', async () => {
    const u = await app.register(1);
    app.markVerified(u.id);
    expect((await app.req('POST', '/api/verification/telegram', { token: u.token })).status).toBe(409);
  });

  it('retries channel delivery with backoff when Telegram fails', async () => {
    const u = await app.register(1);
    const s = await start(u.token);
    await app.tg({ ...tgUser(555), text: `/start ${s.tgToken}` });
    const realFetch = app.fetchMock;
    app.fetchMock = async (input, init) => {
      const body = JSON.parse(String(init?.body ?? '{}'));
      if (body.chat_id === '-100123') return new Response(JSON.stringify({ ok: false, description: 'Too Many Requests', parameters: { retry_after: 30 } }));
      return realFetch(input, init);
    };
    await contact(555, '213550100001');
    const row = app.db.raw.prepare('SELECT status, attempts, next_attempt_at FROM channel_outbox').get() as { status: string; attempts: number; next_attempt_at: number };
    expect(row.status).toBe('pending');
    expect(row.attempts).toBe(1);
    expect(row.next_attempt_at).toBeGreaterThan(app.now);
    app.fetchMock = realFetch;
    const { flushChannelOutbox } = await import('../src/services/channel');
    app.now += 3 * 3600_000;
    await flushChannelOutbox(app.env, app.fetchMock, app.now);
    expect((app.db.raw.prepare('SELECT status FROM channel_outbox').get() as { status: string }).status).toBe('sent');
  });
});

describe('admin channel detection', () => {
  it('records the channel id privately when the bot becomes a channel admin', async () => {
    const r = await app.req('POST', '/telegram/webhook', {
      headers: { 'x-telegram-bot-api-secret-token': 'webhook-secret-xyz' },
      body: { update_id: 9, my_chat_member: { chat: { id: -1001234, type: 'channel', title: 'إدارة' }, from: { id: 1 }, new_chat_member: { status: 'administrator', user: { id: 2, is_bot: true }, can_post_messages: true } } },
    });
    expect(r.status).toBe(200);
    const row = app.db.raw.prepare(`SELECT value, is_public FROM application_settings WHERE key = 'telegram_detected_channel'`).get() as { value: string; is_public: number };
    expect(JSON.parse(row.value)).toMatchObject({ id: -1001234, canPost: true });
    expect(row.is_public).toBe(0);
    expect((await app.req('GET', '/api/settings')).body.settings.telegram_detected_channel).toBeUndefined();
  });
});
