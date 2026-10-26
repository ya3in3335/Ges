import { beforeEach, describe, expect, it } from 'vitest';
import { TestApp } from './helpers';

const OWNER = 4242;
let app: TestApp;
beforeEach(() => { app = new TestApp({ TELEGRAM_APPROVER_IDS: String(OWNER) }); });

const request = (name = 'سمير', ip = app.freshIp()) =>
  app.req('POST', '/api/auth/device-requests', { ip, body: { name, deviceName: 'Samsung A54' } });
const poll = (id: string, secret: string) => app.req('GET', `/api/auth/device-requests/${id}`, { headers: { 'x-request-secret': secret } });
const press = (data: string, from = OWNER, updateId?: number) =>
  app.req('POST', '/telegram/webhook', {
    headers: { 'x-telegram-bot-api-secret-token': 'webhook-secret-xyz' },
    body: { update_id: updateId ?? app.update++, callback_query: { id: 'cb1', from: { id: from }, data, message: { message_id: 7, chat: { id: OWNER }, text: 'طلب' } } },
  });

describe('supervisor activation approved from Telegram', () => {
  it('notifies the owner with accept/reject buttons and unlocks the device on accept', async () => {
    const r = await request();
    expect(r.status).toBe(201);
    const msg = app.telegram.find((t) => t.method === 'sendMessage' && t.body.chat_id === OWNER)!;
    expect(msg.body.text).toContain('سمير');
    const buttons = msg.body.reply_markup.inline_keyboard.flat().map((b: { callback_data: string }) => b.callback_data);
    expect(buttons).toEqual([`dr:${r.body.requestId}:e`, `dr:${r.body.requestId}:a`, `dr:${r.body.requestId}:s`, `dr:${r.body.requestId}:r`]);
    // the secret is never stored in clear
    expect(JSON.stringify(app.db.raw.prepare('SELECT * FROM device_requests').all())).not.toContain(r.body.secret);

    expect((await poll(r.body.requestId, r.body.secret)).body.status).toBe('pending');
    await press(`dr:${r.body.requestId}:e`);
    expect(app.telegram.some((t) => t.method === 'editMessageText' && String(t.body.text).includes('تم القبول'))).toBe(true);

    const ok = await poll(r.body.requestId, r.body.secret);
    expect(ok.body.status).toBe('approved');
    expect(ok.body.user.role).toBe('EDITOR');
    expect(ok.body.tokens.refreshTokenExpiresAt - app.now).toBeGreaterThan(300 * 86_400_000);
    expect((await app.req('POST', '/api/admin/news', { token: ok.body.tokens.accessToken, body: { titleAr: 'خبر تجريبي', bodyAr: 'نص' } })).status).toBe(201);
    // tokens are handed out only once
    expect((await poll(r.body.requestId, r.body.secret)).body).toEqual({ status: 'claimed' });
  });

  it('ignores buttons pressed by anyone other than the configured approver', async () => {
    const r = await request();
    await press(`dr:${r.body.requestId}:s`, 999);
    expect((await poll(r.body.requestId, r.body.secret)).body.status).toBe('pending');
    expect(app.db.raw.prepare(`SELECT COUNT(*) AS n FROM verification_audit WHERE event = 'device_decision_denied'`).get()).toEqual({ n: 1 });
  });

  it('rejects, and a decision cannot be changed afterwards', async () => {
    const r = await request();
    await press(`dr:${r.body.requestId}:r`);
    await press(`dr:${r.body.requestId}:s`);
    expect((await poll(r.body.requestId, r.body.secret)).body.status).toBe('rejected');
    expect(app.db.raw.prepare(`SELECT COUNT(*) AS n FROM users WHERE is_staff_only = 1`).get()).toEqual({ n: 0 });
  });

  it('requires the secret to poll (no guessing by id)', async () => {
    const r = await request();
    await press(`dr:${r.body.requestId}:a`);
    expect((await poll(r.body.requestId, 'wrong')).status).toBe(404);
    expect((await app.req('GET', `/api/auth/device-requests/${r.body.requestId}`)).status).toBe(404);
  });

  it('expires pending requests after 24 hours', async () => {
    const r = await request();
    app.now += 25 * 3600_000;
    await press(`dr:${r.body.requestId}:e`);
    expect((await poll(r.body.requestId, r.body.secret)).body.status).toBe('expired');
  });

  it('rate-limits requests so the owner is not spammed', async () => {
    const statuses: number[] = [];
    for (let i = 0; i < 7; i++) statuses.push((await request('Spam', '6.6.6.6')).status);
    expect(statuses.filter((s) => s === 201)).toHaveLength(5);
    expect(statuses[5]).toBe(429);
  });

  it('is disabled until an approver is configured', async () => {
    const plain = new TestApp();
    expect((await plain.req('POST', '/api/auth/device-requests', { body: { name: 'سمير' } })).status).toBe(503);
  });

  it('tells a user their Telegram id with /id', async () => {
    await app.tg({ from: { id: 555, is_bot: false }, chat: { id: 555, type: 'private' }, text: '/id' });
    expect(app.telegram.at(-1)!.body.text).toContain('555');
  });
});
