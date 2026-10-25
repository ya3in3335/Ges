import { z } from 'zod';
import type { Ctx } from '../env';
import { badRequest, json, notFound, readJson, unauthorized } from '../lib/http';
import { hashPassword, passwordPolicyError, verifyPassword } from '../lib/password';
import { rateLimit } from '../lib/ratelimit';
import { requireUser } from '../lib/rbac';
import { publicUser, type UserRow } from './auth';

export async function getMe(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  const [user, link] = await Promise.all([
    ctx.env.DB.prepare('SELECT * FROM users WHERE id = ?').bind(me.id).first<UserRow>(),
    ctx.env.DB.prepare('SELECT 1 AS x FROM telegram_account_links WHERE user_id = ?').bind(me.id).first(),
  ]);
  if (!user || user.status !== 'active') throw notFound();
  return json({ user: publicUser(user, !!link) });
}

const updateSchema = z.object({
  fullName: z.string().trim().min(3).max(100).regex(/^[\p{L}\p{M}' .-]+$/u).optional(),
  locale: z.enum(['ar', 'fr']).optional(),
  preferences: z.object({
    notifyNews: z.boolean(),
    notifyActivities: z.boolean(),
    notifyPolls: z.boolean(),
    notifyVolunteer: z.boolean(),
    showNamePublic: z.boolean(),
  }).partial().optional(),
}).strict();

export async function updateMe(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  const body = await readJson(ctx.req, updateSchema);
  const sets: string[] = [];
  const vals: unknown[] = [];
  const add = (col: string, v: unknown) => { sets.push(`${col} = ?`); vals.push(v); };
  if (body.fullName !== undefined) add('full_name', body.fullName);
  if (body.locale !== undefined) add('locale', body.locale);
  const p = body.preferences ?? {};
  if (p.notifyNews !== undefined) add('pref_notify_news', p.notifyNews ? 1 : 0);
  if (p.notifyActivities !== undefined) add('pref_notify_activities', p.notifyActivities ? 1 : 0);
  if (p.notifyPolls !== undefined) add('pref_notify_polls', p.notifyPolls ? 1 : 0);
  if (p.notifyVolunteer !== undefined) add('pref_notify_volunteer', p.notifyVolunteer ? 1 : 0);
  if (p.showNamePublic !== undefined) add('pref_show_name_public', p.showNamePublic ? 1 : 0);
  if (sets.length) {
    add('updated_at', ctx.now);
    // Column names come from the fixed list above; values are bound parameters.
    await ctx.env.DB.prepare(`UPDATE users SET ${sets.join(', ')} WHERE id = ?`).bind(...vals, me.id).run();
  }
  return getMe(ctx);
}

const changePwSchema = z.object({ currentPassword: z.string().max(128), newPassword: z.string().max(128) });

export async function changePassword(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  await rateLimit(ctx.env.DB, `changepw:${me.id}`, 5, 3600, ctx.now);
  const body = await readJson(ctx.req, changePwSchema);
  const user = await ctx.env.DB.prepare('SELECT password_hash FROM users WHERE id = ?').bind(me.id).first<{ password_hash: string }>();
  if (!user || !(await verifyPassword(body.currentPassword, user.password_hash))) throw unauthorized('invalid_credentials');
  const policy = passwordPolicyError(body.newPassword);
  if (policy) throw badRequest(policy);
  await ctx.env.DB.batch([
    ctx.env.DB.prepare('UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?').bind(await hashPassword(body.newPassword), ctx.now, me.id),
    ctx.env.DB.prepare('UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ? AND id <> ? AND revoked_at IS NULL').bind(ctx.now, me.id, me.sessionId),
  ]);
  return json({ ok: true });
}

/**
 * Account deletion: personal data is erased/anonymised. Poll participation rows are kept
 * (without personal data) so that past election tallies remain consistent.
 */
export async function deleteMe(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  await rateLimit(ctx.env.DB, `deleteme:${me.id}`, 5, 3600, ctx.now);
  const { password } = await readJson(ctx.req, z.object({ password: z.string().max(128) }));
  const user = await ctx.env.DB.prepare('SELECT password_hash, role FROM users WHERE id = ?').bind(me.id).first<{ password_hash: string; role: string }>();
  if (!user || !(await verifyPassword(password, user.password_hash))) throw unauthorized('invalid_credentials');
  if (user.role === 'SUPER_ADMIN') {
    const others = await ctx.env.DB.prepare(`SELECT COUNT(*) AS n FROM users WHERE role = 'SUPER_ADMIN' AND status = 'active' AND id <> ?`).bind(me.id).first<{ n: number }>();
    if (!others?.n) throw badRequest('last_super_admin');
  }
  const db = ctx.env.DB;
  await db.batch([
    db.prepare(
      `UPDATE users SET status = 'deleted', full_name = 'deleted', email = ?, phone_e164 = ?, password_hash = '!',
       role = 'USER', email_verified_at = NULL, phone_verified_at = NULL, updated_at = ? WHERE id = ?`,
    ).bind(`deleted-${me.id}@invalid`, `deleted:${me.id}`, ctx.now, me.id),
    db.prepare('UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL').bind(ctx.now, me.id),
    db.prepare('DELETE FROM telegram_account_links WHERE user_id = ?').bind(me.id),
    db.prepare('DELETE FROM volunteers WHERE user_id = ?').bind(me.id),
    db.prepare('DELETE FROM push_devices WHERE user_id = ?').bind(me.id),
    db.prepare('DELETE FROM activity_registrations WHERE user_id = ?').bind(me.id),
    db.prepare('DELETE FROM auth_codes WHERE user_id = ?').bind(me.id),
    db.prepare(`UPDATE verification_audit SET telegram_user_id = NULL WHERE user_id = ?`).bind(me.id),
  ]);
  return json({ ok: true });
}

const deviceSchema = z.object({ token: z.string().min(20).max(4096), locale: z.enum(['ar', 'fr']).default('ar') });

/** Registers an FCM token. Works anonymously (topic broadcasts) or linked to the signed-in user. */
export async function registerDevice(ctx: Ctx): Promise<Response> {
  await rateLimit(ctx.env.DB, `device:${ctx.ip}`, 30, 3600, ctx.now);
  const body = await readJson(ctx.req, deviceSchema);
  await ctx.env.DB.prepare(
    `INSERT INTO push_devices (token, user_id, locale, created_at, updated_at) VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(token) DO UPDATE SET user_id = excluded.user_id, locale = excluded.locale, updated_at = excluded.updated_at`,
  ).bind(body.token, ctx.user?.id ?? null, body.locale, ctx.now, ctx.now).run();
  return json({ ok: true });
}

export async function unregisterDevice(ctx: Ctx): Promise<Response> {
  const { token } = await readJson(ctx.req, z.object({ token: z.string().min(20).max(4096) }));
  await ctx.env.DB.prepare('DELETE FROM push_devices WHERE token = ?').bind(token).run();
  return json({ ok: true });
}

export async function myNotifications(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  const rows = await ctx.env.DB.prepare(
    `SELECT id, topic, title_ar, title_fr, body_ar, body_fr, deep_link, created_at FROM notifications
     WHERE (target_user_id = ? OR target_user_id IS NULL) AND delivery <> 'failed' ORDER BY created_at DESC LIMIT 50`,
  ).bind(me.id).all();
  return json({ items: rows.results });
}
