import { z } from 'zod';
import type { Ctx, Role } from '../env';
import { HttpError, json, notFound, readJson } from '../lib/http';
import { randomToken, sha256Hex, timingSafeEqual, uuid } from '../lib/crypto';
import { rateLimit } from '../lib/ratelimit';
import { issueTokens } from '../lib/session';
import { sendMessage, tgCall } from '../services/telegramApi';
import { publicUser, type UserRow } from './auth';

const REQUEST_TTL_MS = 24 * 3600_000;
const ROLES: Record<string, Role> = { e: 'EDITOR', a: 'ADMIN', s: 'SUPER_ADMIN' };
const ROLE_AR: Record<string, string> = { EDITOR: 'محرر', ADMIN: 'مشرف', SUPER_ADMIN: 'مشرف عام' };

export const approverIds = (ctx: Pick<Ctx, 'env'>): number[] =>
  (ctx.env.TELEGRAM_APPROVER_IDS ?? '').split(',').map((s) => parseInt(s.trim(), 10)).filter((n) => Number.isFinite(n) && n > 0);

const requestSchema = z.object({
  name: z.string().trim().min(2).max(60).regex(/^[\p{L}\p{M}\p{N}' .-]+$/u, 'invalid_name'),
  deviceName: z.string().trim().max(80).optional(),
  // "stats": the census admin app; the approver picks a census level instead of an association role.
  app: z.enum(['admin', 'stats']).default('admin'),
});
const STATS_LEVELS: Record<string, 'officer' | 'manager'> = { o: 'officer', m: 'manager' };
const STATS_AR: Record<string, string> = { officer: 'موظف إحصاء', manager: 'مسؤول إحصاء' };

/**
 * A supervisors' app asks to be activated. The owner receives a Telegram message with
 * Accept (choose role) / Reject buttons. Only the app holding the returned secret can collect the session.
 */
export async function createDeviceRequest(ctx: Ctx): Promise<Response> {
  const approvers = approverIds(ctx);
  if (!approvers.length || !ctx.env.TELEGRAM_BOT_TOKEN) throw new HttpError(503, 'approval_not_configured');
  await rateLimit(ctx.env.DB, `devreq:${ctx.ip}`, 5, 3600, ctx.now);
  await rateLimit(ctx.env.DB, 'devreq:global', 30, 3600, ctx.now); // protects the owner from spam
  const b = await readJson(ctx.req, requestSchema);
  const id = uuid();
  const secret = randomToken(32);
  await ctx.env.DB.prepare(
    `INSERT INTO device_requests (id, secret_hash, name, device_name, ip, status, kind, created_at, expires_at) VALUES (?, ?, ?, ?, ?, 'pending', ?, ?, ?)`,
  ).bind(id, await sha256Hex(secret), b.name, b.deviceName ?? null, ctx.ip, b.app, ctx.now, ctx.now + REQUEST_TTL_MS).run();

  const date = new Date(ctx.now).toLocaleString('fr-DZ', { timeZone: 'Africa/Algiers', dateStyle: 'short', timeStyle: 'short' });
  const title = b.app === 'stats' ? 'طلب تفعيل تطبيق إدارة الإحصاء' : 'طلب تفعيل تطبيق المشرفين';
  const text = `🔐 ${title}\nالاسم: ${b.name}\nالجهاز: ${b.deviceName ?? '—'}\nالتاريخ: ${date}\n\nاقبل فقط إذا كنت تعرف هذا الشخص.`;
  const keyboard = b.app === 'stats' ? {
    inline_keyboard: [
      [{ text: '✅ موظف إحصاء', callback_data: `dr:${id}:o` }, { text: '✅ مسؤول إحصاء', callback_data: `dr:${id}:m` }],
      [{ text: '❌ رفض', callback_data: `dr:${id}:r` }],
    ],
  } : {
    inline_keyboard: [
      [{ text: '✅ محرر', callback_data: `dr:${id}:e` }, { text: '✅ مشرف', callback_data: `dr:${id}:a` }],
      [{ text: '✅ مشرف عام', callback_data: `dr:${id}:s` }, { text: '❌ رفض', callback_data: `dr:${id}:r` }],
    ],
  };
  for (const chat of approvers) await sendMessage(ctx, chat, text, keyboard);
  return json({ requestId: id, secret, expiresAt: ctx.now + REQUEST_TTL_MS }, 201);
}

/** Polled by the requesting app. Tokens are handed out exactly once, to the holder of the secret. */
export async function pollDeviceRequest(ctx: Ctx): Promise<Response> {
  await rateLimit(ctx.env.DB, `devpoll:${ctx.ip}`, 120, 600, ctx.now);
  const secret = ctx.req.headers.get('x-request-secret') ?? '';
  const r = await ctx.env.DB.prepare('SELECT * FROM device_requests WHERE id = ?').bind(ctx.params.id).first<{
    id: string; secret_hash: string; name: string; device_name: string | null; status: string; user_id: string | null; expires_at: number;
  }>();
  if (!r || !timingSafeEqual(await sha256Hex(secret), r.secret_hash)) throw notFound();
  if (r.status === 'pending' && r.expires_at <= ctx.now) return json({ status: 'expired' });
  if (r.status !== 'approved' || !r.user_id) return json({ status: r.status });

  const user = await ctx.env.DB.prepare(`SELECT * FROM users WHERE id = ? AND status = 'active'`).bind(r.user_id).first<UserRow>();
  if (!user) return json({ status: 'rejected' });
  const { tokens, insert, sessionId } = await issueTokens(ctx, user, 'admin', undefined, { longLived: true, deviceName: r.device_name });
  const res = await ctx.env.DB.batch([
    ctx.env.DB.prepare(`UPDATE device_requests SET status = 'claimed' WHERE id = ? AND status = 'approved'`).bind(r.id),
    insert,
    ctx.env.DB.prepare(`INSERT INTO admin_audit (actor_id, actor_role, action, entity_type, entity_id, detail, ip, created_at) VALUES (?, ?, 'device_activated', 'session', ?, ?, ?, ?)`)
      .bind(user.id, user.role, sessionId, JSON.stringify({ via: 'telegram_approval', request: r.id }), ctx.ip, ctx.now),
  ]);
  if (!res[0]?.meta.changes) return json({ status: 'claimed' });
  return json({ status: 'approved', user: publicUser(user), tokens });
}

interface CallbackQuery { id: string; from: { id: number }; data?: string; message?: { message_id: number; chat: { id: number }; text?: string } }

/** Accept / reject buttons pressed in Telegram. Only configured approvers are obeyed. */
export async function handleDeviceDecision(ctx: Ctx, q: CallbackQuery): Promise<void> {
  const answer = (text: string) => tgCall(ctx.env, ctx.fetch, 'answerCallbackQuery', { callback_query_id: q.id, text, show_alert: false });
  const m = /^dr:([0-9a-f-]{36}):([easrom])$/.exec(q.data ?? '');
  if (!m) { await answer('—'); return; }
  if (!approverIds(ctx).includes(q.from.id)) {
    await ctx.env.DB.prepare(`INSERT INTO verification_audit (telegram_user_id, event, detail, created_at) VALUES (?, 'device_decision_denied', ?, ?)`).bind(q.from.id, m[1], ctx.now).run();
    await answer('غير مسموح');
    return;
  }
  const [, id, action] = m as unknown as [string, string, string];
  const r = await ctx.env.DB.prepare('SELECT * FROM device_requests WHERE id = ?').bind(id).first<{ id: string; name: string; status: string; kind: string; expires_at: number }>();
  // A census request can only be accepted as a census level, a supervisors' request only as a role.
  const fits = action === 'r' || (r?.kind === 'stats' ? action in STATS_LEVELS : action in ROLES);
  if (!r || r.status !== 'pending' || r.expires_at <= ctx.now || !fits) {
    await answer('تمت معالجة هذا الطلب مسبقاً أو انتهت صلاحيته');
    return;
  }
  const db = ctx.env.DB;
  let result: string;
  if (action === 'r') {
    await db.prepare(`UPDATE device_requests SET status = 'rejected', decided_by_tg = ?, decided_at = ? WHERE id = ? AND status = 'pending'`).bind(q.from.id, ctx.now, id).run();
    result = '❌ تم الرفض';
  } else if (r.kind === 'stats') {
    const level = STATS_LEVELS[action]!;
    const userId = uuid();
    const pending = `EXISTS (SELECT 1 FROM device_requests WHERE id = ? AND status = 'pending' AND expires_at > ?)`;
    const res = await db.batch([
      // A census-only account (no password, ordinary association role) with its census level.
      db.prepare(
        `INSERT INTO users (id, full_name, email, phone_e164, password_hash, role, status, is_staff_only, created_at, updated_at)
         SELECT ?, ?, ?, ?, '!', 'USER', 'active', 1, ?, ? WHERE ${pending}`,
      ).bind(userId, r.name, `stats-${userId}@staff.invalid`, `staff:${userId}`, ctx.now, ctx.now, id, ctx.now),
      db.prepare(`INSERT INTO stats_staff (user_id, level, granted_by, created_at) SELECT ?, ?, NULL, ? WHERE EXISTS (SELECT 1 FROM users WHERE id = ?)`)
        .bind(userId, level, ctx.now, userId),
      db.prepare(
        `UPDATE device_requests SET status = 'approved', stats_level = ?, user_id = ?, decided_by_tg = ?, decided_at = ?
         WHERE id = ? AND status = 'pending' AND EXISTS (SELECT 1 FROM users WHERE id = ?)`,
      ).bind(level, userId, q.from.id, ctx.now, id, userId),
      db.prepare(`INSERT INTO admin_audit (action, entity_type, entity_id, detail, created_at) SELECT 'stats.device_approved', 'user', ?, ?, ? WHERE EXISTS (SELECT 1 FROM users WHERE id = ?)`)
        .bind(userId, JSON.stringify({ level, approverTelegramId: q.from.id }), ctx.now, userId),
    ]);
    result = res[2]?.meta.changes ? `✅ تم القبول (${STATS_AR[level]})` : 'تمت معالجة هذا الطلب مسبقاً';
  } else {
    const role = ROLES[action]!;
    const userId = uuid();
    const pending = `EXISTS (SELECT 1 FROM device_requests WHERE id = ? AND status = 'pending' AND expires_at > ?)`;
    const res = await db.batch([
      // A staff-only account (no password) is created for the supervisor, then linked to the request.
      db.prepare(
        `INSERT INTO users (id, full_name, email, phone_e164, password_hash, role, status, is_staff_only, created_at, updated_at)
         SELECT ?, ?, ?, ?, '!', ?, 'active', 1, ?, ? WHERE ${pending}`,
      ).bind(userId, r.name, `staff-${userId}@staff.invalid`, `staff:${userId}`, role, ctx.now, ctx.now, id, ctx.now),
      db.prepare(
        `UPDATE device_requests SET status = 'approved', role = ?, user_id = ?, decided_by_tg = ?, decided_at = ?
         WHERE id = ? AND status = 'pending' AND EXISTS (SELECT 1 FROM users WHERE id = ?)`,
      ).bind(role, userId, q.from.id, ctx.now, id, userId),
      db.prepare(`INSERT INTO admin_audit (action, entity_type, entity_id, detail, created_at) SELECT 'device_request_approved', 'user', ?, ?, ? WHERE EXISTS (SELECT 1 FROM users WHERE id = ?)`)
        .bind(userId, JSON.stringify({ role, approverTelegramId: q.from.id }), ctx.now, userId),
    ]);
    result = res[1]?.meta.changes ? `✅ تم القبول (${ROLE_AR[role]})` : 'تمت معالجة هذا الطلب مسبقاً';
  }
  await answer(result);
  if (q.message) {
    await tgCall(ctx.env, ctx.fetch, 'editMessageText', {
      chat_id: q.message.chat.id, message_id: q.message.message_id,
      text: `${q.message.text ?? ''}\n\n${result}`.slice(0, 4000),
    });
  }
}
