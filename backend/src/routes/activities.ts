import { z } from 'zod';
import type { Ctx } from '../env';
import { conflict, HttpError, json, notFound, pagination, readJson } from '../lib/http';
import { uuid } from '../lib/crypto';
import { rateLimit } from '../lib/ratelimit';
import { requireRole, requireUser } from '../lib/rbac';
import { adminAudit } from '../lib/audit';
import { queueNotification } from './notifications';

interface ActivityRow {
  id: string; title_ar: string; title_fr: string | null; description_ar: string; description_fr: string | null;
  location: string | null; latitude: number | null; longitude: number | null; starts_at: number; ends_at: number;
  capacity: number | null; image_url: string | null; video_url: string | null; is_campaign: number; featured: number;
  status: string; registration_open: number; created_at: number; updated_at: number; registered?: number;
}

function phase(a: ActivityRow, now: number) {
  if (a.status === 'cancelled') return 'cancelled';
  if (now < a.starts_at) return 'upcoming';
  if (now < a.ends_at) return 'ongoing';
  return 'completed';
}

const dto = (a: ActivityRow, now: number) => ({
  id: a.id, titleAr: a.title_ar, titleFr: a.title_fr, descriptionAr: a.description_ar, descriptionFr: a.description_fr,
  location: a.location, latitude: a.latitude, longitude: a.longitude, startsAt: a.starts_at, endsAt: a.ends_at,
  capacity: a.capacity, registeredCount: a.registered ?? 0, imageUrl: a.image_url, videoUrl: a.video_url,
  isCampaign: !!a.is_campaign, featured: !!a.featured, status: a.status, phase: phase(a, now),
  registrationOpen: !!a.registration_open && now < a.ends_at && a.status === 'published', updatedAt: a.updated_at,
});

const COUNT = `(SELECT COUNT(*) FROM activity_registrations r WHERE r.activity_id = a.id AND r.status <> 'cancelled') AS registered`;

export async function listActivities(ctx: Ctx): Promise<Response> {
  const { limit, offset, page } = pagination(ctx.url);
  const when = ctx.url.searchParams.get('phase') ?? 'upcoming';
  const campaigns = ctx.url.searchParams.get('campaigns') === '1';
  let cond = '';
  let order = 'a.starts_at ASC';
  if (when === 'upcoming') cond = 'AND a.starts_at > ?1';
  else if (when === 'ongoing') cond = 'AND a.starts_at <= ?1 AND a.ends_at > ?1';
  else if (when === 'completed') { cond = 'AND a.ends_at <= ?1'; order = 'a.starts_at DESC'; }
  else if (when === 'current') cond = 'AND a.ends_at > ?1';
  const rows = await ctx.env.DB.prepare(
    `SELECT a.*, ${COUNT} FROM activities a WHERE a.status IN ('published','cancelled') ${cond} ${campaigns ? 'AND a.is_campaign = 1' : ''}
     ORDER BY ${order} LIMIT ?2 OFFSET ?3`,
  ).bind(ctx.now, limit + 1, offset).all<ActivityRow>();
  return json({ items: rows.results.slice(0, limit).map((a) => dto(a, ctx.now)), page, hasMore: rows.results.length > limit }, 200, { 'cache-control': 'public, max-age=60' });
}

export async function getActivity(ctx: Ctx): Promise<Response> {
  const a = await ctx.env.DB.prepare(`SELECT a.*, ${COUNT} FROM activities a WHERE a.id = ? AND a.status IN ('published','cancelled')`).bind(ctx.params.id).first<ActivityRow>();
  if (!a) throw notFound();
  let myStatus: string | null = null;
  if (ctx.user) {
    const r = await ctx.env.DB.prepare('SELECT status FROM activity_registrations WHERE activity_id = ? AND user_id = ?').bind(a.id, ctx.user.id).first<{ status: string }>();
    myStatus = r?.status ?? null;
  }
  const media = await ctx.env.DB.prepare(`SELECT id, kind, url, thumbnail_url, title_ar FROM media WHERE activity_id = ? AND status = 'published' ORDER BY created_at DESC LIMIT 30`).bind(a.id).all();
  return json({ item: dto(a, ctx.now), myRegistration: myStatus, media: media.results });
}

export async function registerForActivity(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  await rateLimit(ctx.env.DB, `actreg:${me.id}`, 20, 3600, ctx.now);
  const a = await ctx.env.DB.prepare(`SELECT a.*, ${COUNT} FROM activities a WHERE a.id = ? AND a.status = 'published'`).bind(ctx.params.id).first<ActivityRow>();
  if (!a) throw notFound();
  if (!a.registration_open || ctx.now >= a.ends_at) throw new HttpError(409, 'registration_closed');
  // Capacity is enforced inside the INSERT so concurrent registrations cannot overfill.
  const r = await ctx.env.DB.prepare(
    `INSERT INTO activity_registrations (activity_id, user_id, status, created_at, updated_at)
     SELECT ?1, ?2, 'registered', ?3, ?3
     WHERE (SELECT capacity FROM activities WHERE id = ?1) IS NULL
        OR (SELECT COUNT(*) FROM activity_registrations WHERE activity_id = ?1 AND status <> 'cancelled') < (SELECT capacity FROM activities WHERE id = ?1)
     ON CONFLICT(activity_id, user_id) DO UPDATE SET status = 'registered', updated_at = excluded.updated_at WHERE activity_registrations.status = 'cancelled'`,
  ).bind(a.id, me.id, ctx.now).run();
  if (!r.meta.changes) {
    const existing = await ctx.env.DB.prepare('SELECT status FROM activity_registrations WHERE activity_id = ? AND user_id = ?').bind(a.id, me.id).first<{ status: string }>();
    if (existing && existing.status !== 'cancelled') throw conflict('already_registered');
    throw conflict('activity_full');
  }
  return json({ ok: true, status: 'registered' }, 201);
}

export async function cancelActivityRegistration(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  await ctx.env.DB.prepare(`UPDATE activity_registrations SET status = 'cancelled', updated_at = ? WHERE activity_id = ? AND user_id = ? AND status = 'registered'`)
    .bind(ctx.now, ctx.params.id, me.id).run();
  return json({ ok: true });
}

export async function myActivities(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  const rows = await ctx.env.DB.prepare(
    `SELECT a.*, ${COUNT}, r.status AS my_status FROM activity_registrations r JOIN activities a ON a.id = r.activity_id
     WHERE r.user_id = ? AND r.status <> 'cancelled' ORDER BY a.starts_at DESC LIMIT 100`,
  ).bind(me.id).all<ActivityRow & { my_status: string }>();
  return json({ items: rows.results.map((a) => ({ ...dto(a, ctx.now), myRegistration: a.my_status })) });
}

const activitySchema = z.object({
  titleAr: z.string().trim().min(3).max(200),
  titleFr: z.string().trim().max(200).nullish(),
  descriptionAr: z.string().trim().min(1).max(20_000),
  descriptionFr: z.string().trim().max(20_000).nullish(),
  location: z.string().trim().max(300).nullish(),
  latitude: z.number().min(-90).max(90).nullish(),
  longitude: z.number().min(-180).max(180).nullish(),
  startsAt: z.number().int().positive(),
  endsAt: z.number().int().positive(),
  capacity: z.number().int().positive().max(100_000).nullish(),
  imageUrl: z.string().max(500).refine((u) => u.startsWith('https://') || u.startsWith('/media/')).nullish(),
  videoUrl: z.string().url().startsWith('https://').max(500).nullish(),
  isCampaign: z.boolean().default(false),
  featured: z.boolean().default(false),
  status: z.enum(['draft', 'published', 'cancelled', 'archived']).default('draft'),
  registrationOpen: z.boolean().default(true),
  notify: z.boolean().default(false),
});

export async function adminListActivities(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'EDITOR');
  const { limit, offset, page } = pagination(ctx.url);
  const rows = await ctx.env.DB.prepare(`SELECT a.*, ${COUNT} FROM activities a ORDER BY a.starts_at DESC LIMIT ? OFFSET ?`).bind(limit + 1, offset).all<ActivityRow>();
  return json({ items: rows.results.slice(0, limit).map((a) => dto(a, ctx.now)), page, hasMore: rows.results.length > limit });
}

export async function adminCreateActivity(ctx: Ctx): Promise<Response> {
  const u = requireRole(ctx, 'EDITOR');
  const b = await readJson(ctx.req, activitySchema.refine((x) => x.endsAt >= x.startsAt, { message: 'ends_before_start', path: ['endsAt'] }));
  const id = uuid();
  await ctx.env.DB.batch([
    ctx.env.DB.prepare(
      `INSERT INTO activities (id, title_ar, title_fr, description_ar, description_fr, location, latitude, longitude, starts_at, ends_at, capacity,
        image_url, video_url, is_campaign, featured, status, registration_open, created_by, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(id, b.titleAr, b.titleFr ?? null, b.descriptionAr, b.descriptionFr ?? null, b.location ?? null, b.latitude ?? null, b.longitude ?? null,
      b.startsAt, b.endsAt, b.capacity ?? null, b.imageUrl ?? null, b.videoUrl ?? null, b.isCampaign ? 1 : 0, b.featured ? 1 : 0, b.status,
      b.registrationOpen ? 1 : 0, u.id, ctx.now, ctx.now),
    adminAudit(ctx, 'create', 'activity', id, { status: b.status }),
  ]);
  if (b.notify && b.status === 'published') {
    ctx.waitUntil(queueNotification(ctx, { topic: 'activities', titleAr: b.titleAr, titleFr: b.titleFr, bodyAr: b.descriptionAr.slice(0, 140), bodyFr: b.descriptionFr?.slice(0, 140), deepLink: `app://activities/${id}` }));
  }
  return json({ id }, 201);
}

export async function adminUpdateActivity(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'EDITOR');
  const b = await readJson(ctx.req, activitySchema.partial());
  const a = await ctx.env.DB.prepare('SELECT * FROM activities WHERE id = ?').bind(ctx.params.id).first<ActivityRow>();
  if (!a) throw notFound();
  const pick = <T,>(v: T | undefined, cur: T) => (v === undefined ? cur : v);
  const startsAt = b.startsAt ?? a.starts_at;
  const endsAt = b.endsAt ?? a.ends_at;
  if (endsAt < startsAt) throw new HttpError(400, 'ends_before_start');
  await ctx.env.DB.batch([
    ctx.env.DB.prepare(
      `UPDATE activities SET title_ar = ?, title_fr = ?, description_ar = ?, description_fr = ?, location = ?, latitude = ?, longitude = ?,
        starts_at = ?, ends_at = ?, capacity = ?, image_url = ?, video_url = ?, is_campaign = ?, featured = ?, status = ?, registration_open = ?, updated_at = ?
       WHERE id = ?`,
    ).bind(b.titleAr ?? a.title_ar, pick(b.titleFr, a.title_fr), b.descriptionAr ?? a.description_ar, pick(b.descriptionFr, a.description_fr),
      pick(b.location, a.location), pick(b.latitude, a.latitude), pick(b.longitude, a.longitude), startsAt, endsAt, pick(b.capacity, a.capacity),
      pick(b.imageUrl, a.image_url), pick(b.videoUrl, a.video_url), b.isCampaign === undefined ? a.is_campaign : +b.isCampaign,
      b.featured === undefined ? a.featured : +b.featured, b.status ?? a.status, b.registrationOpen === undefined ? a.registration_open : +b.registrationOpen, ctx.now, a.id),
    adminAudit(ctx, 'update', 'activity', a.id, Object.keys(b)),
  ]);
  return json({ ok: true });
}

export async function adminActivityParticipants(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'EDITOR');
  const rows = await ctx.env.DB.prepare(
    `SELECT u.id AS user_id, u.full_name, u.phone_e164, u.email, r.status, r.created_at FROM activity_registrations r
     JOIN users u ON u.id = r.user_id WHERE r.activity_id = ? ORDER BY r.created_at`,
  ).bind(ctx.params.id).all();
  return json({ items: rows.results });
}

export async function adminSetParticipantStatus(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'EDITOR');
  const { status } = await readJson(ctx.req, z.object({ status: z.enum(['registered', 'cancelled', 'attended']) }));
  await ctx.env.DB.batch([
    ctx.env.DB.prepare('UPDATE activity_registrations SET status = ?, updated_at = ? WHERE activity_id = ? AND user_id = ?').bind(status, ctx.now, ctx.params.id, ctx.params.userId),
    adminAudit(ctx, 'participant_status', 'activity', ctx.params.id!, { userId: ctx.params.userId, status }),
  ]);
  return json({ ok: true });
}
