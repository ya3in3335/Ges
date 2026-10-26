import { z } from 'zod';
import type { Ctx, Role } from '../env';
import { badRequest, forbidden, json, notFound, pagination, readJson } from '../lib/http';
import { canAssignRole, requireRole, ROLES } from '../lib/rbac';
import { adminAudit } from '../lib/audit';
import { maskPhone } from '../lib/phone';

export async function overview(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'EDITOR');
  const db = ctx.env.DB;
  const day = ctx.now - 86_400_000;
  const week = ctx.now - 7 * 86_400_000;
  const r = await db.batch([
    db.prepare(`SELECT COUNT(*) AS n FROM users WHERE status = 'active' AND is_staff_only = 0`),
    db.prepare(`SELECT COUNT(*) AS n FROM users WHERE status = 'active' AND phone_verified_at IS NOT NULL`),
    db.prepare(`SELECT COUNT(*) AS n FROM users WHERE status = 'active' AND created_at > ?`).bind(week),
    db.prepare(`SELECT COUNT(*) AS n FROM volunteers WHERE status = 'pending'`),
    db.prepare(`SELECT COUNT(*) AS n FROM volunteers WHERE status = 'approved'`),
    db.prepare(`SELECT COUNT(*) AS n FROM polls WHERE status = 'published' AND starts_at <= ?1 AND ends_at > ?1`).bind(ctx.now),
    db.prepare(`SELECT COUNT(*) AS n FROM activities WHERE status = 'published' AND ends_at > ?`).bind(ctx.now),
    db.prepare(`SELECT COUNT(*) AS n FROM news WHERE status = 'published'`),
    db.prepare(`SELECT COUNT(*) AS n FROM telegram_verifications WHERE created_at > ?`).bind(day),
    db.prepare(`SELECT COUNT(*) AS n FROM channel_outbox WHERE status IN ('pending','failed')`),
  ]);
  const n = (i: number) => ((r[i]?.results?.[0] as { n: number } | undefined)?.n ?? 0);
  return json({
    users: n(0), verifiedUsers: n(1), newUsersLast7Days: n(2), pendingVolunteers: n(3), approvedVolunteers: n(4),
    activePolls: n(5), upcomingActivities: n(6), publishedNews: n(7), verificationsLast24h: n(8), channelBacklog: n(9),
  });
}

export async function telegramStats(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'ADMIN');
  const db = ctx.env.DB;
  const since = ctx.now - 30 * 86_400_000;
  const [byStatus, byEvent, daily, outbox] = await db.batch([
    db.prepare(`SELECT status, COUNT(*) AS n FROM telegram_verifications WHERE created_at > ? GROUP BY status`).bind(since),
    db.prepare(`SELECT event, COUNT(*) AS n FROM verification_audit WHERE created_at > ? GROUP BY event ORDER BY n DESC`).bind(since),
    db.prepare(
      `SELECT strftime('%Y-%m-%d', consumed_at / 1000, 'unixepoch') AS day, COUNT(*) AS n FROM telegram_verifications
       WHERE status = 'verified' AND consumed_at > ? GROUP BY day ORDER BY day`,
    ).bind(since),
    db.prepare(`SELECT status, COUNT(*) AS n FROM channel_outbox GROUP BY status`),
  ]);
  return json({
    byStatus: byStatus!.results, byEvent: byEvent!.results, verifiedPerDay: daily!.results, channelOutbox: outbox!.results,
    telegramConfigured: !!(ctx.env.TELEGRAM_BOT_TOKEN && ctx.env.TELEGRAM_BOT_USERNAME), channelConfigured: !!ctx.env.TELEGRAM_ADMIN_CHANNEL_ID,
  });
}

export async function listUsers(ctx: Ctx): Promise<Response> {
  const me = requireRole(ctx, 'ADMIN');
  const { limit, offset, page } = pagination(ctx.url);
  const q = ctx.url.searchParams.get('q')?.trim().slice(0, 100);
  const role = ctx.url.searchParams.get('role');
  const verified = ctx.url.searchParams.get('verified');
  const where = [`u.status <> 'deleted'`];
  const binds: unknown[] = [];
  if (q) {
    const l = `%${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;
    where.push(`(u.full_name LIKE ? ESCAPE '\\' OR u.email LIKE ? ESCAPE '\\' OR u.phone_e164 LIKE ? ESCAPE '\\')`);
    binds.push(l, l, l);
  }
  if (role && (ROLES as string[]).includes(role)) { where.push('u.role = ?'); binds.push(role); }
  if (verified === '1') where.push('u.phone_verified_at IS NOT NULL');
  if (verified === '0') where.push('u.phone_verified_at IS NULL');
  const [rows, total] = await Promise.all([
    ctx.env.DB.prepare(
      `SELECT u.id, u.full_name, u.email, u.phone_e164, u.role, u.status, u.phone_verified_at, u.email_verified_at, u.created_at,
              (l.user_id IS NOT NULL) AS telegram_linked
       FROM users u LEFT JOIN telegram_account_links l ON l.user_id = u.id
       WHERE ${where.join(' AND ')} ORDER BY u.created_at DESC LIMIT ? OFFSET ?`,
    ).bind(...binds, limit, offset).all<Record<string, unknown> & { phone_e164: string }>(),
    ctx.env.DB.prepare(`SELECT COUNT(*) AS n FROM users u WHERE ${where.join(' AND ')}`).bind(...binds).first<{ n: number }>(),
  ]);
  // Full phone numbers are only shown to SUPER_ADMIN (data minimisation).
  const items = rows.results.map((u) => ({ ...u, phone_e164: me.role === 'SUPER_ADMIN' ? u.phone_e164 : maskPhone(u.phone_e164) }));
  return json({ items, total: total?.n ?? 0, page });
}

export async function updateUser(ctx: Ctx): Promise<Response> {
  const me = requireRole(ctx, 'ADMIN');
  const b = await readJson(ctx.req, z.object({ role: z.enum(ROLES as [Role, ...Role[]]).optional(), status: z.enum(['active', 'suspended']).optional() }).strict());
  const target = await ctx.env.DB.prepare(`SELECT id, role, status FROM users WHERE id = ? AND status <> 'deleted'`).bind(ctx.params.id).first<{ id: string; role: Role; status: string }>();
  if (!target) throw notFound();
  if (target.id === me.id) throw forbidden('cannot_modify_self');
  if (b.role && !canAssignRole(me.role, b.role, target.role)) throw forbidden('cannot_assign_role');
  if (b.status && !canAssignRole(me.role, target.role, target.role)) throw forbidden('cannot_modify_higher_role');
  if (target.role === 'SUPER_ADMIN' && (b.role && b.role !== 'SUPER_ADMIN' || b.status === 'suspended')) {
    const n = await ctx.env.DB.prepare(`SELECT COUNT(*) AS n FROM users WHERE role = 'SUPER_ADMIN' AND status = 'active'`).first<{ n: number }>();
    if ((n?.n ?? 0) <= 1) throw badRequest('last_super_admin');
  }
  const stmts = [
    ctx.env.DB.prepare('UPDATE users SET role = ?, status = ?, updated_at = ? WHERE id = ?').bind(b.role ?? target.role, b.status ?? target.status, ctx.now, target.id),
    adminAudit(ctx, 'update_user', 'user', target.id, { from: { role: target.role, status: target.status }, to: b }),
  ];
  // Role/status changes take effect immediately: existing sessions are revoked.
  if ((b.role && b.role !== target.role) || b.status === 'suspended') {
    stmts.push(ctx.env.DB.prepare('UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL').bind(ctx.now, target.id));
  }
  await ctx.env.DB.batch(stmts);
  return json({ ok: true });
}

/** Removes a Telegram link and the phone-verified flag (e.g. a user changed numbers). */
export async function resetVerification(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'ADMIN');
  const db = ctx.env.DB;
  await db.batch([
    db.prepare('DELETE FROM telegram_account_links WHERE user_id = ?').bind(ctx.params.id),
    db.prepare('UPDATE users SET phone_verified_at = NULL, phone_verify_method = NULL, updated_at = ? WHERE id = ?').bind(ctx.now, ctx.params.id),
    db.prepare(`INSERT INTO verification_audit (user_id, event, detail, created_at) VALUES (?, 'admin_reset', ?, ?)`).bind(ctx.params.id, ctx.user!.id, ctx.now),
    adminAudit(ctx, 'reset_verification', 'user', ctx.params.id!),
  ]);
  return json({ ok: true });
}

export async function auditLog(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'ADMIN');
  const { limit, offset, page } = pagination(ctx.url, 100);
  const type = ctx.url.searchParams.get('type');
  const source = ctx.url.searchParams.get('source') ?? 'admin';
  if (source === 'verification') {
    const rows = await ctx.env.DB.prepare(
      `SELECT id, verification_id, user_id, event, detail, created_at FROM verification_audit ORDER BY id DESC LIMIT ? OFFSET ?`,
    ).bind(limit + 1, offset).all();
    return json({ items: rows.results.slice(0, limit), page, hasMore: rows.results.length > limit });
  }
  const rows = await ctx.env.DB.prepare(
    `SELECT a.*, u.full_name AS actor_name FROM admin_audit a LEFT JOIN users u ON u.id = a.actor_id
     ${type ? 'WHERE a.entity_type = ?' : ''} ORDER BY a.id DESC LIMIT ? OFFSET ?`,
  ).bind(...(type ? [type] : []), limit + 1, offset).all();
  return json({ items: rows.results.slice(0, limit), page, hasMore: rows.results.length > limit });
}
