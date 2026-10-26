import { z } from 'zod';
import type { Ctx, Role } from '../env';
import { badRequest, conflict, HttpError, json, readJson, unauthorized } from '../lib/http';
import { DUMMY_HASH, hashPassword, passwordPolicyError, verifyPassword } from '../lib/password';
import { isAlgerianMobile, normalizeAlgerianPhone } from '../lib/phone';
import { rateLimit } from '../lib/ratelimit';
import { issueTokens } from '../lib/session';
import { randomDigits, sha256Hex, uuid } from '../lib/crypto';
import { requireUser } from '../lib/rbac';
import { sendMessage } from '../services/telegramApi';
import { codeEmail, emailConfigured, sendEmail } from '../services/email';

const MAX_FAILED_LOGINS = 10;
const LOCK_MS = 15 * 60_000;

const nameSchema = z.string().trim().min(3).max(100).regex(/^[\p{L}\p{M}' .-]+$/u, 'invalid_name');
const emailSchema = z.string().trim().toLowerCase().email().max(254);

const registerSchema = z.object({
  fullName: nameSchema,
  email: emailSchema,
  phone: z.string().max(32),
  password: z.string().max(128),
  confirmPassword: z.string().max(128),
  locale: z.enum(['ar', 'fr']).optional(),
});

export function publicUser(u: UserRow, telegramLinked = false) {
  return {
    id: u.id,
    fullName: u.full_name,
    email: u.email,
    phone: u.phone_e164,
    role: u.role,
    locale: u.locale,
    emailVerified: !!u.email_verified_at,
    phoneVerified: !!u.phone_verified_at,
    phoneVerifiedAt: u.phone_verified_at,
    telegramLinked,
    preferences: {
      notifyNews: !!u.pref_notify_news,
      notifyActivities: !!u.pref_notify_activities,
      notifyPolls: !!u.pref_notify_polls,
      notifyVolunteer: !!u.pref_notify_volunteer,
      showNamePublic: !!u.pref_show_name_public,
    },
    createdAt: u.created_at,
  };
}

export interface UserRow {
  id: string; full_name: string; email: string; phone_e164: string; password_hash: string; role: Role;
  status: string; email_verified_at: number | null; phone_verified_at: number | null; locale: 'ar' | 'fr';
  pref_notify_news: number; pref_notify_activities: number; pref_notify_polls: number; pref_notify_volunteer: number;
  pref_show_name_public: number; failed_logins: number; locked_until: number | null; created_at: number;
}

export async function register(ctx: Ctx): Promise<Response> {
  await rateLimit(ctx.env.DB, `register:${ctx.ip}`, 5, 3600, ctx.now);
  const body = await readJson(ctx.req, registerSchema);
  const phone = normalizeAlgerianPhone(body.phone);
  if (!phone || !isAlgerianMobile(phone)) throw badRequest('invalid_phone');
  if (body.password !== body.confirmPassword) throw badRequest('password_mismatch');
  const policy = passwordPolicyError(body.password);
  if (policy) throw badRequest(policy);

  const existing = await ctx.env.DB.prepare(
    `SELECT email = ? AS same_email, phone_e164 = ? AS same_phone FROM users WHERE status <> 'deleted' AND (email = ? OR phone_e164 = ?) LIMIT 1`,
  ).bind(body.email, phone, body.email, phone).first<{ same_email: number; same_phone: number }>();
  if (existing) throw conflict(existing.same_email ? 'email_taken' : 'phone_taken');

  const id = uuid();
  const hash = await hashPassword(body.password);
  const user = { id, role: 'USER' as Role };
  const { tokens, insert } = await issueTokens(ctx, user, 'android');
  try {
    await ctx.env.DB.batch([
      ctx.env.DB.prepare(
        `INSERT INTO users (id, full_name, email, phone_e164, password_hash, role, locale, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, 'USER', ?, ?, ?)`,
      ).bind(id, body.fullName, body.email, phone, hash, body.locale ?? 'ar', ctx.now, ctx.now),
      insert,
    ]);
  } catch (e) {
    if (String(e).includes('UNIQUE')) throw conflict('account_exists');
    throw e;
  }
  const row = await ctx.env.DB.prepare('SELECT * FROM users WHERE id = ?').bind(id).first<UserRow>();
  return json({ user: publicUser(row!), tokens, emailVerificationAvailable: emailConfigured(ctx.env) }, 201);
}

const loginSchema = z.object({
  identifier: z.string().trim().min(3).max(254),
  password: z.string().min(1).max(128),
  client: z.enum(['android', 'admin']).default('android'),
});

async function findByIdentifier(ctx: Ctx, identifier: string): Promise<UserRow | null> {
  const phone = normalizeAlgerianPhone(identifier);
  if (phone) {
    return ctx.env.DB.prepare(`SELECT * FROM users WHERE phone_e164 = ? AND status <> 'deleted'`).bind(phone).first<UserRow>();
  }
  return ctx.env.DB.prepare(`SELECT * FROM users WHERE email = ? AND status <> 'deleted'`).bind(identifier.toLowerCase()).first<UserRow>();
}

export async function login(ctx: Ctx): Promise<Response> {
  await rateLimit(ctx.env.DB, `login:ip:${ctx.ip}`, 20, 600, ctx.now);
  const body = await readJson(ctx.req, loginSchema);
  await rateLimit(ctx.env.DB, `login:id:${body.identifier.toLowerCase()}`, 10, 600, ctx.now);
  const user = await findByIdentifier(ctx, body.identifier);
  const ok = await verifyPassword(body.password, user?.password_hash ?? DUMMY_HASH);
  if (!user || user.status !== 'active') throw unauthorized('invalid_credentials');
  if (user.locked_until && user.locked_until > ctx.now) throw new HttpError(423, 'account_locked', 'account_locked', { until: user.locked_until });
  if (!ok) {
    const fails = user.failed_logins + 1;
    await ctx.env.DB.prepare('UPDATE users SET failed_logins = ?, locked_until = ? WHERE id = ?')
      .bind(fails >= MAX_FAILED_LOGINS ? 0 : fails, fails >= MAX_FAILED_LOGINS ? ctx.now + LOCK_MS : null, user.id).run();
    throw unauthorized('invalid_credentials');
  }
  if (body.client === 'admin' && !['SUPER_ADMIN', 'ADMIN', 'EDITOR'].includes(user.role)) {
    throw new HttpError(403, 'insufficient_role');
  }
  const { tokens, insert } = await issueTokens(ctx, user, body.client);
  await ctx.env.DB.batch([
    ctx.env.DB.prepare('UPDATE users SET failed_logins = 0, locked_until = NULL WHERE id = ?').bind(user.id),
    insert,
  ]);
  const link = await ctx.env.DB.prepare('SELECT 1 FROM telegram_account_links WHERE user_id = ?').bind(user.id).first();
  return json({ user: publicUser(user, !!link), tokens });
}

const refreshSchema = z.object({ refreshToken: z.string().min(20).max(200) });

export async function refresh(ctx: Ctx): Promise<Response> {
  await rateLimit(ctx.env.DB, `refresh:${ctx.ip}`, 60, 600, ctx.now);
  const { refreshToken } = await readJson(ctx.req, refreshSchema);
  const hash = await sha256Hex(refreshToken);
  const s = await ctx.env.DB.prepare(
    `SELECT s.id, s.user_id, s.family_id, s.client, s.expires_at, s.rotated_at, s.revoked_at, s.long_lived, s.device_name, u.role, u.status
     FROM auth_sessions s JOIN users u ON u.id = s.user_id WHERE s.refresh_hash = ?`,
  ).bind(hash).first<{ id: string; user_id: string; family_id: string; client: 'android' | 'admin'; expires_at: number; rotated_at: number | null; revoked_at: number | null; long_lived: number; device_name: string | null; role: Role; status: string }>();
  if (!s) throw unauthorized('invalid_refresh_token');
  if (s.rotated_at || s.revoked_at) {
    // Reuse of a rotated token → likely theft. Revoke the whole family.
    await ctx.env.DB.prepare('UPDATE auth_sessions SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL').bind(ctx.now, s.family_id).run();
    throw unauthorized('refresh_token_reused');
  }
  if (s.expires_at <= ctx.now || s.status !== 'active') throw unauthorized('session_expired');
  const { tokens, insert } = await issueTokens(ctx, { id: s.user_id, role: s.role }, s.client, s.family_id, { longLived: !!s.long_lived, deviceName: s.device_name });
  const results = await ctx.env.DB.batch([
    ctx.env.DB.prepare('UPDATE auth_sessions SET rotated_at = ? WHERE id = ? AND rotated_at IS NULL AND revoked_at IS NULL').bind(ctx.now, s.id),
    insert,
  ]);
  if (!results[0]?.meta.changes) {
    // Lost a race with a concurrent refresh using the same token.
    await ctx.env.DB.prepare('UPDATE auth_sessions SET revoked_at = ? WHERE family_id = ?').bind(ctx.now, s.family_id).run();
    throw unauthorized('refresh_token_reused');
  }
  return json({ tokens });
}

export async function logout(ctx: Ctx): Promise<Response> {
  const body = await readJson(ctx.req, refreshSchema.partial());
  if (body.refreshToken) {
    await ctx.env.DB.prepare('UPDATE auth_sessions SET revoked_at = ? WHERE refresh_hash = ? AND revoked_at IS NULL')
      .bind(ctx.now, await sha256Hex(body.refreshToken)).run();
  } else if (ctx.user) {
    await ctx.env.DB.prepare('UPDATE auth_sessions SET revoked_at = ? WHERE id = ?').bind(ctx.now, ctx.user.sessionId).run();
  }
  return json({ ok: true });
}

// ───────────── Password recovery ─────────────
// Codes are delivered through the linked Telegram account (free) and/or e-mail when configured.

const forgotSchema = z.object({ identifier: z.string().trim().min(3).max(254) });

export async function forgotPassword(ctx: Ctx): Promise<Response> {
  await rateLimit(ctx.env.DB, `forgot:ip:${ctx.ip}`, 10, 3600, ctx.now);
  const { identifier } = await readJson(ctx.req, forgotSchema);
  await rateLimit(ctx.env.DB, `forgot:id:${identifier.toLowerCase()}`, 3, 3600, ctx.now);
  const user = await findByIdentifier(ctx, identifier);
  const channels: string[] = [];
  if (user && user.status === 'active') {
    const code = randomDigits(6);
    await ctx.env.DB.batch([
      ctx.env.DB.prepare(`UPDATE auth_codes SET used_at = ? WHERE user_id = ? AND purpose = 'password_reset' AND used_at IS NULL`).bind(ctx.now, user.id),
      ctx.env.DB.prepare(
        `INSERT INTO auth_codes (id, user_id, purpose, code_hash, created_at, expires_at) VALUES (?, ?, 'password_reset', ?, ?, ?)`,
      ).bind(uuid(), user.id, await sha256Hex(`${user.id}:${code}`), ctx.now, ctx.now + 15 * 60_000),
    ]);
    const link = await ctx.env.DB.prepare('SELECT telegram_chat_id FROM telegram_account_links WHERE user_id = ?').bind(user.id).first<{ telegram_chat_id: number }>();
    const msg = user.locale === 'fr'
      ? `Code de réinitialisation du mot de passe : ${code}\nValable 15 minutes. Ne le partagez avec personne.`
      : `رمز إعادة تعيين كلمة المرور: ${code}\nصالح لمدة 15 دقيقة. لا تشاركه مع أي شخص.`;
    if (link) {
      const r = await sendMessage(ctx, link.telegram_chat_id, msg);
      if (r.ok) channels.push('telegram');
    }
    if (emailConfigured(ctx.env) && user.email_verified_at) {
      const m = codeEmail('reset', code, 15);
      if (await sendEmail(ctx.env, ctx.fetch, user.email, m.subject, m.text, m.html)) channels.push('email');
    }
  }
  // Identical response whether or not the account exists (no account enumeration).
  return json({ ok: true, message: 'if_account_exists_code_sent' });
}

const resetSchema = z.object({
  identifier: z.string().trim().min(3).max(254),
  code: z.string().regex(/^\d{6}$/),
  newPassword: z.string().max(128),
});

export async function resetPassword(ctx: Ctx): Promise<Response> {
  await rateLimit(ctx.env.DB, `reset:ip:${ctx.ip}`, 20, 3600, ctx.now);
  const body = await readJson(ctx.req, resetSchema);
  const policy = passwordPolicyError(body.newPassword);
  if (policy) throw badRequest(policy);
  const user = await findByIdentifier(ctx, body.identifier);
  if (!user) throw badRequest('invalid_code');
  const code = await ctx.env.DB.prepare(
    `SELECT id, code_hash, attempts FROM auth_codes WHERE user_id = ? AND purpose = 'password_reset' AND used_at IS NULL AND expires_at > ?
     ORDER BY created_at DESC LIMIT 1`,
  ).bind(user.id, ctx.now).first<{ id: string; code_hash: string; attempts: number }>();
  if (!code || code.attempts >= 5) throw badRequest('invalid_code');
  if ((await sha256Hex(`${user.id}:${body.code}`)) !== code.code_hash) {
    await ctx.env.DB.prepare('UPDATE auth_codes SET attempts = attempts + 1 WHERE id = ?').bind(code.id).run();
    throw badRequest('invalid_code');
  }
  const hash = await hashPassword(body.newPassword);
  const res = await ctx.env.DB.batch([
    ctx.env.DB.prepare('UPDATE auth_codes SET used_at = ? WHERE id = ? AND used_at IS NULL').bind(ctx.now, code.id),
    ctx.env.DB.prepare(
      `UPDATE users SET password_hash = ?, failed_logins = 0, locked_until = NULL, updated_at = ?
       WHERE id = ? AND EXISTS (SELECT 1 FROM auth_codes WHERE id = ? AND used_at = ?)`,
    ).bind(hash, ctx.now, user.id, code.id, ctx.now),
    // Sign out everywhere after a password reset.
    ctx.env.DB.prepare('UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL').bind(ctx.now, user.id),
  ]);
  if (!res[0]?.meta.changes) throw badRequest('invalid_code');
  return json({ ok: true });
}

// ───────────── E-mail verification (only when a provider is configured) ─────────────

export async function sendEmailVerification(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  if (!emailConfigured(ctx.env)) throw new HttpError(503, 'email_not_configured');
  await rateLimit(ctx.env.DB, `emailverify:${me.id}`, 3, 3600, ctx.now);
  const user = await ctx.env.DB.prepare('SELECT * FROM users WHERE id = ?').bind(me.id).first<UserRow>();
  if (!user || user.email_verified_at) return json({ ok: true, alreadyVerified: true });
  const code = randomDigits(6);
  // Only the latest code is valid.
  await ctx.env.DB.batch([
    ctx.env.DB.prepare(`UPDATE auth_codes SET used_at = ? WHERE user_id = ? AND purpose = 'email_verify' AND used_at IS NULL`).bind(ctx.now, me.id),
    ctx.env.DB.prepare(
      `INSERT INTO auth_codes (id, user_id, purpose, code_hash, created_at, expires_at) VALUES (?, ?, 'email_verify', ?, ?, ?)`,
    ).bind(uuid(), me.id, await sha256Hex(`${me.id}:${code}`), ctx.now, ctx.now + 30 * 60_000),
  ]);
  const m = codeEmail('verify', code, 30);
  const sent = await sendEmail(ctx.env, ctx.fetch, user.email, m.subject, m.text, m.html);
  if (!sent) throw new HttpError(502, 'email_delivery_failed');
  return json({ ok: true });
}

export async function confirmEmailVerification(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  await rateLimit(ctx.env.DB, `emailconfirm:${me.id}`, 10, 3600, ctx.now);
  const { code } = await readJson(ctx.req, z.object({ code: z.string().regex(/^\d{6}$/) }));
  const row = await ctx.env.DB.prepare(
    `SELECT id FROM auth_codes WHERE user_id = ? AND purpose = 'email_verify' AND used_at IS NULL AND expires_at > ? AND code_hash = ?`,
  ).bind(me.id, ctx.now, await sha256Hex(`${me.id}:${code}`)).first<{ id: string }>();
  if (!row) throw badRequest('invalid_code');
  await ctx.env.DB.batch([
    ctx.env.DB.prepare('UPDATE auth_codes SET used_at = ? WHERE id = ?').bind(ctx.now, row.id),
    ctx.env.DB.prepare('UPDATE users SET email_verified_at = ?, updated_at = ? WHERE id = ?').bind(ctx.now, ctx.now, me.id),
  ]);
  return json({ ok: true });
}
