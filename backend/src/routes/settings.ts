import { z } from 'zod';
import type { Ctx } from '../env';
import { badRequest, json, readJson } from '../lib/http';
import { requireRole } from '../lib/rbac';
import { adminAudit } from '../lib/audit';

/**
 * Association details are managed entirely by administrators. Nothing is pre-filled:
 * empty values are shown as "not provided yet" by the clients.
 */
export const PUBLIC_SETTING_KEYS = [
  'association_name_ar', 'association_name_fr', 'association_short_description_ar', 'association_short_description_fr',
  'about_ar', 'about_fr', 'contact_phone', 'contact_email', 'contact_address_ar', 'contact_address_fr',
  'facebook_url', 'instagram_url', 'youtube_url', 'website_url', 'telegram_channel_url',
  'latest_app_version', 'latest_app_version_code', 'apk_url', 'apk_sha256', 'min_supported_version_code',
] as const;

const donationDto = (d: Record<string, unknown> | null) => ({
  ccpAccount: d?.ccp_account ?? null, ccpKey: d?.ccp_key ?? null, ccpHolder: d?.ccp_holder ?? null,
  bankName: d?.bank_name ?? null, bankAccount: d?.bank_account ?? null, bankHolder: d?.bank_holder ?? null,
  instructionsAr: d?.instructions_ar ?? null, instructionsFr: d?.instructions_fr ?? null,
  contactPhone: d?.contact_phone ?? null, contactEmail: d?.contact_email ?? null, updatedAt: d?.updated_at ?? 0,
});

export async function publicSettings(db: D1Database): Promise<Record<string, string>> {
  const rows = await db.prepare('SELECT key, value FROM application_settings WHERE is_public = 1').all<{ key: string; value: string }>();
  return Object.fromEntries(rows.results.map((r) => [r.key, r.value]));
}

export async function getSettings(ctx: Ctx): Promise<Response> {
  return json({ settings: await publicSettings(ctx.env.DB) }, 200, { 'cache-control': 'public, max-age=300' });
}

export async function getDonations(ctx: Ctx): Promise<Response> {
  const d = await ctx.env.DB.prepare('SELECT * FROM donation_settings WHERE id = 1').first<Record<string, unknown>>();
  const campaigns = await ctx.env.DB.prepare(
    `SELECT id, title_ar, title_fr, description_ar, description_fr, image_url, starts_at, ends_at FROM activities
     WHERE status = 'published' AND is_campaign = 1 AND ends_at > ? ORDER BY starts_at LIMIT 20`,
  ).bind(ctx.now).all();
  return json({ donation: donationDto(d), campaigns: campaigns.results }, 200, { 'cache-control': 'public, max-age=300' });
}

const opt = (max: number) => z.string().trim().max(max).nullish();
const donationSchema = z.object({
  ccpAccount: opt(30), ccpKey: opt(5), ccpHolder: opt(120), bankName: opt(120), bankAccount: opt(40), bankHolder: opt(120),
  instructionsAr: opt(4000), instructionsFr: opt(4000), contactPhone: opt(30), contactEmail: opt(254),
}).strict();

export async function adminUpdateDonations(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'ADMIN');
  const b = await readJson(ctx.req, donationSchema);
  if (b.ccpAccount && !/^\d{1,12}$/.test(b.ccpAccount)) throw badRequest('invalid_ccp');
  if (b.bankAccount && !/^[0-9 ]{10,40}$/.test(b.bankAccount)) throw badRequest('invalid_rib');
  const n = (v: string | null | undefined) => (v ? v : null);
  await ctx.env.DB.batch([
    ctx.env.DB.prepare(
      `UPDATE donation_settings SET ccp_account = ?, ccp_key = ?, ccp_holder = ?, bank_name = ?, bank_account = ?, bank_holder = ?,
       instructions_ar = ?, instructions_fr = ?, contact_phone = ?, contact_email = ?, updated_by = ?, updated_at = ? WHERE id = 1`,
    ).bind(n(b.ccpAccount), n(b.ccpKey), n(b.ccpHolder), n(b.bankName), n(b.bankAccount), n(b.bankHolder), n(b.instructionsAr), n(b.instructionsFr),
      n(b.contactPhone), n(b.contactEmail), ctx.user!.id, ctx.now),
    adminAudit(ctx, 'update', 'donation_settings', '1'),
  ]);
  return getDonations(ctx);
}

const settingsSchema = z.record(z.enum(PUBLIC_SETTING_KEYS), z.string().trim().max(4000));

export async function adminUpdateSettings(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'SUPER_ADMIN');
  const b = await readJson(ctx.req, settingsSchema);
  for (const [k, v] of Object.entries(b)) {
    if (k.endsWith('_url') && v && !v.startsWith('https://')) throw badRequest(`invalid_url:${k}`);
  }
  const stmts = Object.entries(b).map(([k, v]) =>
    v === ''
      ? ctx.env.DB.prepare('DELETE FROM application_settings WHERE key = ?').bind(k)
      : ctx.env.DB.prepare(
        `INSERT INTO application_settings (key, value, is_public, updated_by, updated_at) VALUES (?, ?, 1, ?, ?)
         ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_by = excluded.updated_by, updated_at = excluded.updated_at`,
      ).bind(k, v, ctx.user!.id, ctx.now));
  stmts.push(adminAudit(ctx, 'update', 'settings', null, Object.keys(b)));
  await ctx.env.DB.batch(stmts);
  return json({ settings: await publicSettings(ctx.env.DB) });
}
