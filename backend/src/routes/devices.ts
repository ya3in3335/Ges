import { z } from 'zod';
import type { Ctx, Role } from '../env';
import { badRequest, forbidden, HttpError, json, notFound, readJson } from '../lib/http';
import { randomDigits, sha256Hex, uuid } from '../lib/crypto';
import { rateLimit } from '../lib/ratelimit';
import { canAssignRole, requireRole } from '../lib/rbac';
import { adminAudit } from '../lib/audit';
import { issueTokens } from '../lib/session';
import { publicUser, type UserRow } from './auth';

const CODE_TTL_MS = 48 * 3600_000;
const STAFF_ROLES = ['EDITOR', 'ADMIN', 'SUPER_ADMIN'] as const;

const codeSchema = z.union([
  // Code for an existing account (must already be EDITOR or above).
  z.object({ userId: z.string().uuid() }).strict(),
  // New supervisor without a member account: a staff-only account is created.
  z.object({ name: z.string().trim().min(2).max(100), role: z.enum(STAFF_ROLES) }).strict(),
]);

/** Issues a single-use 8-digit activation code (valid 48 h) for the supervisors' app. */
export async function createActivationCode(ctx: Ctx): Promise<Response> {
  const me = requireRole(ctx, 'ADMIN');
  await rateLimit(ctx.env.DB, `actcode:${me.id}`, 30, 3600, ctx.now);
  const b = await readJson(ctx.req, codeSchema);
  const db = ctx.env.DB;
  let userId: string;
  let name: string;
  let role: Role;
  const stmts: D1PreparedStatement[] = [];
  if ('userId' in b) {
    const u = await db.prepare(`SELECT id, full_name, role FROM users WHERE id = ? AND status = 'active'`).bind(b.userId).first<{ id: string; full_name: string; role: Role }>();
    if (!u) throw notFound();
    if (!(STAFF_ROLES as readonly string[]).includes(u.role)) throw badRequest('not_staff');
    if (u.id !== me.id && !canAssignRole(me.role, u.role, u.role)) throw forbidden('cannot_modify_higher_role');
    userId = u.id; name = u.full_name; role = u.role;
  } else {
    if (!canAssignRole(me.role, b.role, 'USER')) throw forbidden('cannot_assign_role');
    userId = uuid(); name = b.name; role = b.role;
    stmts.push(db.prepare(
      `INSERT INTO users (id, full_name, email, phone_e164, password_hash, role, status, is_staff_only, created_at, updated_at)
       VALUES (?, ?, ?, ?, '!', ?, 'active', 1, ?, ?)`,
    ).bind(userId, name, `staff-${userId}@staff.invalid`, `staff:${userId}`, role, ctx.now, ctx.now));
  }
  const code = randomDigits(8);
  stmts.push(
    db.prepare(`INSERT INTO activation_codes (id, user_id, code_hash, created_by, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)`)
      .bind(uuid(), userId, await sha256Hex(`activation:${code}`), me.id, ctx.now, ctx.now + CODE_TTL_MS),
    adminAudit(ctx, 'create_activation_code', 'user', userId, { role }),
  );
  await db.batch(stmts);
  return json({ code, userId, name, role, expiresAt: ctx.now + CODE_TTL_MS }, 201);
}

const activateSchema = z.object({ code: z.string().regex(/^\d{8}$/), deviceName: z.string().trim().max(80).optional() });

/** Exchanges an activation code for a long-lived, revocable device session. */
export async function activateDevice(ctx: Ctx): Promise<Response> {
  await rateLimit(ctx.env.DB, `activate:${ctx.ip}`, 10, 3600, ctx.now);
  const b = await readJson(ctx.req, activateSchema);
  const db = ctx.env.DB;
  const row = await db.prepare(
    `SELECT a.id AS code_id, u.* FROM activation_codes a JOIN users u ON u.id = a.user_id
     WHERE a.code_hash = ? AND a.used_at IS NULL AND a.expires_at > ? AND u.status = 'active'`,
  ).bind(await sha256Hex(`activation:${b.code}`), ctx.now).first<UserRow & { code_id: string }>();
  if (!row || !(STAFF_ROLES as readonly string[]).includes(row.role)) throw new HttpError(400, 'invalid_code');
  const { tokens, insert, sessionId } = await issueTokens(ctx, row, 'admin', undefined, { longLived: true, deviceName: b.deviceName ?? null });
  const res = await db.batch([
    db.prepare('UPDATE activation_codes SET used_at = ?, session_id = ? WHERE id = ? AND used_at IS NULL').bind(ctx.now, sessionId, row.code_id),
    insert,
    db.prepare(`INSERT INTO admin_audit (actor_id, actor_role, action, entity_type, entity_id, detail, ip, created_at) VALUES (?, ?, 'device_activated', 'session', ?, ?, ?, ?)`)
      .bind(row.id, row.role, sessionId, JSON.stringify({ device: b.deviceName ?? null }), ctx.ip, ctx.now),
  ]);
  if (!res[0]?.meta.changes) throw new HttpError(400, 'invalid_code'); // used concurrently
  return json({ user: publicUser(row), tokens }, 201);
}

/** Active supervisor devices (long-lived sessions). */
export async function listDevices(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'ADMIN');
  const rows = await ctx.env.DB.prepare(
    `SELECT s.id, s.family_id, s.device_name, s.created_at, s.last_used_at, u.id AS user_id, u.full_name, u.role
     FROM auth_sessions s JOIN users u ON u.id = s.user_id
     WHERE s.long_lived = 1 AND s.revoked_at IS NULL AND s.rotated_at IS NULL AND s.expires_at > ?
     ORDER BY s.created_at DESC LIMIT 200`,
  ).bind(ctx.now).all();
  return json({ items: rows.results });
}

/** Disconnects a device immediately (its whole refresh-token family is revoked). */
export async function revokeDevice(ctx: Ctx): Promise<Response> {
  const me = requireRole(ctx, 'ADMIN');
  const s = await ctx.env.DB.prepare(
    `SELECT s.family_id, u.id AS user_id, u.role FROM auth_sessions s JOIN users u ON u.id = s.user_id WHERE s.id = ? AND s.long_lived = 1`,
  ).bind(ctx.params.id).first<{ family_id: string; user_id: string; role: Role }>();
  if (!s) throw notFound();
  if (s.user_id !== me.id && !canAssignRole(me.role, s.role, s.role)) throw forbidden('cannot_modify_higher_role');
  await ctx.env.DB.batch([
    ctx.env.DB.prepare('UPDATE auth_sessions SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL').bind(ctx.now, s.family_id),
    adminAudit(ctx, 'revoke_device', 'session', ctx.params.id!),
  ]);
  return json({ ok: true });
}
