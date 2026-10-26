import { z } from 'zod';
import type { Ctx } from '../env';
import { badRequest, conflict, forbidden, HttpError, json, notFound, pagination, readJson } from '../lib/http';
import { uuid } from '../lib/crypto';
import { rateLimit } from '../lib/ratelimit';
import { requireRole, requireUser } from '../lib/rbac';
import { adminAudit } from '../lib/audit';
import { queueNotification } from './notifications';

interface LectureRow {
  id: string; kind: string; title_ar: string; title_fr: string | null; description_ar: string | null; description_fr: string | null;
  speaker: string | null; location: string | null; online_url: string | null; starts_at: number; ends_at: number;
  capacity: number | null; registration_open: number; status: string; created_at: number; updated_at: number; registered?: number;
}

const COUNT = `(SELECT COUNT(*) FROM lecture_registrations r WHERE r.lecture_id = l.id AND r.status <> 'cancelled') AS registered`;

const dto = (l: LectureRow, now: number) => ({
  id: l.id, kind: l.kind, titleAr: l.title_ar, titleFr: l.title_fr, descriptionAr: l.description_ar, descriptionFr: l.description_fr,
  speaker: l.speaker, location: l.location, onlineUrl: l.online_url, startsAt: l.starts_at, endsAt: l.ends_at,
  capacity: l.capacity, registeredCount: l.registered ?? 0, status: l.status,
  phase: l.status === 'cancelled' ? 'cancelled' : now < l.starts_at ? 'upcoming' : now < l.ends_at ? 'ongoing' : 'completed',
  registrationOpen: !!l.registration_open && l.status === 'published' && now < l.ends_at,
});

// ───────────── Member form ─────────────

const name = z.string().trim().min(2).max(60).regex(/^[\p{L}\p{M}' .-]+$/u, 'invalid_name');
const thisYear = () => new Date().getUTCFullYear();

export const profileSchema = z.object({
  firstName: name,
  lastName: name,
  gender: z.enum(['male', 'female']),
  birthYear: z.number().int().min(1920).refine((y) => y <= thisYear() - 5, 'invalid_birth_year'),
  wilaya: z.string().trim().min(2).max(60),
  maritalStatus: z.enum(['single', 'married', 'divorced', 'widowed']),
  childrenCount: z.number().int().min(0).max(30).nullish(),
  occupation: z.enum(['university_student', 'pupil', 'worker', 'unemployed', 'retired', 'other']),
  occupationDetail: z.string().trim().max(120).nullish(),
}).superRefine((p, c) => {
  // Children are asked only of members who are or have been married.
  if (p.maritalStatus !== 'single' && (p.childrenCount === null || p.childrenCount === undefined)) {
    c.addIssue({ code: 'custom', path: ['childrenCount'], message: 'required' });
  }
  // Students, pupils and workers say what they study / their level / their profession.
  if (['university_student', 'pupil', 'worker', 'other'].includes(p.occupation) && !p.occupationDetail) {
    c.addIssue({ code: 'custom', path: ['occupationDetail'], message: 'required' });
  }
});

const registerSchema = z.object({ profile: profileSchema, note: z.string().trim().max(500).nullish() });

interface ProfileRow {
  first_name: string; last_name: string; gender: string | null; birth_year: number | null; wilaya: string | null;
  marital_status: string | null; children_count: number | null; occupation: string | null; occupation_detail: string | null;
}
const profileDto = (p: ProfileRow) => ({
  firstName: p.first_name, lastName: p.last_name, gender: p.gender, birthYear: p.birth_year, wilaya: p.wilaya,
  maritalStatus: p.marital_status, childrenCount: p.children_count, occupation: p.occupation, occupationDetail: p.occupation_detail,
});

/** Registration needs a confirmed contact: the e-mail address or the phone number. */
async function contactCheck(ctx: Ctx, userId: string) {
  const u = await ctx.env.DB.prepare('SELECT email, phone_e164, email_verified_at, phone_verified_at, status FROM users WHERE id = ?')
    .bind(userId).first<{ email: string; phone_e164: string; email_verified_at: number | null; phone_verified_at: number | null; status: string }>();
  if (!u || u.status !== 'active') throw forbidden('account_inactive');
  return { user: u, verified: !!(u.email_verified_at || u.phone_verified_at) };
}

// ───────────── Public ─────────────

export async function listLectures(ctx: Ctx): Promise<Response> {
  const { limit, offset, page } = pagination(ctx.url);
  const past = ctx.url.searchParams.get('when') === 'past';
  const rows = await ctx.env.DB.prepare(
    `SELECT l.*, ${COUNT} FROM lectures l WHERE l.status IN ('published','cancelled') AND ${past ? 'l.ends_at <= ?1' : 'l.ends_at > ?1'}
     ORDER BY l.starts_at ${past ? 'DESC' : 'ASC'} LIMIT ?2 OFFSET ?3`,
  ).bind(ctx.now, limit + 1, offset).all<LectureRow>();
  let mine = new Map<string, string>();
  if (ctx.user && rows.results.length) {
    const ids = rows.results.map((r) => r.id);
    const regs = await ctx.env.DB.prepare(
      `SELECT lecture_id, status FROM lecture_registrations WHERE user_id = ? AND lecture_id IN (${ids.map(() => '?').join(',')})`,
    ).bind(ctx.user.id, ...ids).all<{ lecture_id: string; status: string }>();
    mine = new Map(regs.results.map((r) => [r.lecture_id, r.status]));
  }
  return json({
    items: rows.results.slice(0, limit).map((l) => ({ ...dto(l, ctx.now), myRegistration: mine.get(l.id) ?? null })),
    page, hasMore: rows.results.length > limit,
  });
}

export async function getLecture(ctx: Ctx): Promise<Response> {
  const l = await ctx.env.DB.prepare(`SELECT l.*, ${COUNT} FROM lectures l WHERE l.id = ? AND l.status IN ('published','cancelled')`).bind(ctx.params.id).first<LectureRow>();
  if (!l) throw notFound();
  let my: { status: string } | null = null;
  let canRegister: { verified: boolean } | null = null;
  if (ctx.user) {
    my = await ctx.env.DB.prepare('SELECT status FROM lecture_registrations WHERE lecture_id = ? AND user_id = ?').bind(l.id, ctx.user.id).first<{ status: string }>();
    canRegister = { verified: (await contactCheck(ctx, ctx.user.id)).verified };
  }
  return json({ item: dto(l, ctx.now), myRegistration: my?.status ?? null, contactVerified: canRegister?.verified ?? false });
}

/** The member's saved answers plus the contact data that is filled automatically (read-only in the form). */
export async function myLectureProfile(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  const { user, verified } = await contactCheck(ctx, me.id);
  const p = await ctx.env.DB.prepare('SELECT * FROM member_profiles WHERE user_id = ?').bind(me.id).first<ProfileRow>();
  return json({
    profile: p ? profileDto(p) : null,
    phone: user.phone_e164, phoneVerified: !!user.phone_verified_at,
    email: user.email, emailVerified: !!user.email_verified_at,
    contactVerified: verified,
  });
}

export async function registerForLecture(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  await rateLimit(ctx.env.DB, `lecreg:${me.id}`, 20, 3600, ctx.now);
  const b = await readJson(ctx.req, registerSchema);
  const { user, verified } = await contactCheck(ctx, me.id);
  if (!verified) throw forbidden('contact_not_verified');
  const l = await ctx.env.DB.prepare(`SELECT * FROM lectures WHERE id = ? AND status = 'published'`).bind(ctx.params.id).first<LectureRow>();
  if (!l) throw notFound();
  if (!l.registration_open || ctx.now >= l.ends_at) throw new HttpError(409, 'registration_closed');
  const p = b.profile;
  const children = p.maritalStatus === 'single' ? null : p.childrenCount ?? 0;
  const detail = p.occupationDetail || null;
  const db = ctx.env.DB;
  // Capacity is enforced inside the INSERT so concurrent registrations cannot overfill.
  const res = await db.batch([
    db.prepare(
      `INSERT INTO lecture_registrations (lecture_id, user_id, status, first_name, last_name, phone_e164, email, gender, birth_year, wilaya,
         marital_status, children_count, occupation, occupation_detail, note, created_at, updated_at)
       SELECT ?1, ?2, 'registered', ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?15
       WHERE (SELECT capacity FROM lectures WHERE id = ?1) IS NULL
          OR (SELECT COUNT(*) FROM lecture_registrations WHERE lecture_id = ?1 AND status <> 'cancelled' AND user_id <> ?2) < (SELECT capacity FROM lectures WHERE id = ?1)
       ON CONFLICT(lecture_id, user_id) DO UPDATE SET status = 'registered', first_name = excluded.first_name, last_name = excluded.last_name,
         phone_e164 = excluded.phone_e164, email = excluded.email, gender = excluded.gender, birth_year = excluded.birth_year, wilaya = excluded.wilaya,
         marital_status = excluded.marital_status, children_count = excluded.children_count, occupation = excluded.occupation,
         occupation_detail = excluded.occupation_detail, note = excluded.note, updated_at = excluded.updated_at
       WHERE lecture_registrations.status <> 'attended'`,
    ).bind(l.id, me.id, p.firstName, p.lastName, user.phone_e164, user.email_verified_at ? user.email : null, p.gender, p.birthYear, p.wilaya,
      p.maritalStatus, children, p.occupation, detail, b.note ?? null, ctx.now),
    db.prepare(
      `INSERT INTO member_profiles (user_id, first_name, last_name, gender, birth_year, wilaya, marital_status, children_count, occupation, occupation_detail, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(user_id) DO UPDATE SET first_name = excluded.first_name, last_name = excluded.last_name, gender = excluded.gender,
         birth_year = excluded.birth_year, wilaya = excluded.wilaya, marital_status = excluded.marital_status, children_count = excluded.children_count,
         occupation = excluded.occupation, occupation_detail = excluded.occupation_detail, updated_at = excluded.updated_at`,
    ).bind(me.id, p.firstName, p.lastName, p.gender, p.birthYear, p.wilaya, p.maritalStatus, children, p.occupation, detail, ctx.now),
  ]);
  if (!res[0]?.meta.changes) {
    const existing = await db.prepare('SELECT status FROM lecture_registrations WHERE lecture_id = ? AND user_id = ?').bind(l.id, me.id).first<{ status: string }>();
    if (existing?.status === 'attended') throw conflict('already_attended');
    throw conflict('lecture_full');
  }
  return json({ ok: true, status: 'registered' }, 201);
}

export async function cancelLectureRegistration(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  const r = await ctx.env.DB.prepare(`UPDATE lecture_registrations SET status = 'cancelled', updated_at = ? WHERE lecture_id = ? AND user_id = ? AND status = 'registered'`)
    .bind(ctx.now, ctx.params.id, me.id).run();
  if (!r.meta.changes) throw conflict('not_registered');
  return json({ ok: true });
}

// ───────────── Administration ─────────────

const lectureSchema = z.object({
  kind: z.enum(['lecture', 'meeting', 'training']).default('lecture'),
  titleAr: z.string().trim().min(3).max(200),
  titleFr: z.string().trim().max(200).nullish(),
  descriptionAr: z.string().trim().max(10_000).nullish(),
  descriptionFr: z.string().trim().max(10_000).nullish(),
  speaker: z.string().trim().max(200).nullish(),
  location: z.string().trim().max(300).nullish(),
  onlineUrl: z.string().url().startsWith('https://').max(500).nullish(),
  startsAt: z.number().int().positive(),
  endsAt: z.number().int().positive(),
  capacity: z.number().int().positive().max(100_000).nullish(),
  registrationOpen: z.boolean().default(true),
  status: z.enum(['draft', 'published', 'cancelled']).default('draft'),
  notify: z.boolean().default(true),
});

export async function adminListLectures(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'EDITOR');
  const rows = await ctx.env.DB.prepare(`SELECT l.*, ${COUNT} FROM lectures l ORDER BY l.starts_at DESC LIMIT 200`).all<LectureRow>();
  return json({ items: rows.results.map((l) => ({ ...dto(l, ctx.now), registrationOpenFlag: !!l.registration_open })) });
}

export async function adminCreateLecture(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'EDITOR');
  const b = await readJson(ctx.req, lectureSchema);
  if (b.endsAt <= b.startsAt) throw badRequest('ends_before_start');
  const id = uuid();
  await ctx.env.DB.batch([
    ctx.env.DB.prepare(
      `INSERT INTO lectures (id, kind, title_ar, title_fr, description_ar, description_fr, speaker, location, online_url, starts_at, ends_at,
         capacity, registration_open, status, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(id, b.kind, b.titleAr, b.titleFr ?? null, b.descriptionAr ?? null, b.descriptionFr ?? null, b.speaker ?? null, b.location ?? null,
      b.onlineUrl ?? null, b.startsAt, b.endsAt, b.capacity ?? null, b.registrationOpen ? 1 : 0, b.status, ctx.user!.id, ctx.now, ctx.now),
    adminAudit(ctx, 'create', 'lecture', id, { status: b.status }),
  ]);
  if (b.status === 'published' && b.notify) await announce(ctx, id, b.titleAr, b.titleFr, b.kind);
  return json({ id }, 201);
}

async function announce(ctx: Ctx, id: string, titleAr: string, titleFr: string | null | undefined, kind: string) {
  const label = kind === 'meeting' ? { ar: 'اجتماع جديد', fr: 'Nouvelle réunion' } : kind === 'training' ? { ar: 'دورة تكوينية جديدة', fr: 'Nouvelle formation' } : { ar: 'محاضرة جديدة', fr: 'Nouvelle conférence' };
  ctx.waitUntil(queueNotification(ctx, { topic: 'activities', titleAr: label.ar, titleFr: label.fr, bodyAr: titleAr, bodyFr: titleFr ?? titleAr, deepLink: `app://lectures/${id}` }));
}

export async function adminUpdateLecture(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'EDITOR');
  const b = await readJson(ctx.req, lectureSchema.partial());
  const l = await ctx.env.DB.prepare('SELECT * FROM lectures WHERE id = ?').bind(ctx.params.id).first<LectureRow>();
  if (!l) throw notFound();
  const startsAt = b.startsAt ?? l.starts_at;
  const endsAt = b.endsAt ?? l.ends_at;
  if (endsAt <= startsAt) throw badRequest('ends_before_start');
  const pick = <T>(v: T | undefined, cur: T) => (v === undefined ? cur : v);
  await ctx.env.DB.batch([
    ctx.env.DB.prepare(
      `UPDATE lectures SET kind = ?, title_ar = ?, title_fr = ?, description_ar = ?, description_fr = ?, speaker = ?, location = ?, online_url = ?,
         starts_at = ?, ends_at = ?, capacity = ?, registration_open = ?, status = ?, updated_at = ? WHERE id = ?`,
    ).bind(pick(b.kind, l.kind), pick(b.titleAr, l.title_ar), pick(b.titleFr, l.title_fr), pick(b.descriptionAr, l.description_ar),
      pick(b.descriptionFr, l.description_fr), pick(b.speaker, l.speaker), pick(b.location, l.location), pick(b.onlineUrl, l.online_url),
      startsAt, endsAt, pick(b.capacity, l.capacity), b.registrationOpen === undefined ? l.registration_open : b.registrationOpen ? 1 : 0,
      pick(b.status, l.status), ctx.now, l.id),
    adminAudit(ctx, 'update', 'lecture', l.id, b),
  ]);
  if (l.status !== 'published' && b.status === 'published' && b.notify !== false) await announce(ctx, l.id, b.titleAr ?? l.title_ar, b.titleFr ?? l.title_fr, b.kind ?? l.kind);
  return json({ ok: true });
}

export async function adminDeleteLecture(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'ADMIN');
  const r = await ctx.env.DB.prepare('DELETE FROM lectures WHERE id = ?').bind(ctx.params.id).run();
  if (!r.meta.changes) throw notFound();
  await adminAudit(ctx, 'delete', 'lecture', ctx.params.id!).run();
  return json({ ok: true });
}

/** Personal data of registrants: visible to editors who organise the lecture; every access is audited. */
export async function adminLectureRegistrations(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'EDITOR');
  const l = await ctx.env.DB.prepare('SELECT id FROM lectures WHERE id = ?').bind(ctx.params.id).first();
  if (!l) throw notFound();
  const rows = await ctx.env.DB.prepare(
    `SELECT user_id, status, first_name, last_name, phone_e164, email, gender, birth_year, wilaya, marital_status, children_count,
       occupation, occupation_detail, note, created_at FROM lecture_registrations WHERE lecture_id = ? ORDER BY created_at`,
  ).bind(ctx.params.id).all<Record<string, unknown>>();
  await adminAudit(ctx, 'view_registrations', 'lecture', ctx.params.id!).run();
  return json({
    items: rows.results.map((r) => ({
      userId: r.user_id, status: r.status, firstName: r.first_name, lastName: r.last_name, phone: r.phone_e164, email: r.email,
      gender: r.gender, birthYear: r.birth_year, wilaya: r.wilaya, maritalStatus: r.marital_status, childrenCount: r.children_count,
      occupation: r.occupation, occupationDetail: r.occupation_detail, note: r.note, createdAt: r.created_at,
    })),
  });
}

export async function adminSetLectureRegistration(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'EDITOR');
  const { status } = await readJson(ctx.req, z.object({ status: z.enum(['registered', 'cancelled', 'attended']) }));
  const r = await ctx.env.DB.prepare('UPDATE lecture_registrations SET status = ?, updated_at = ? WHERE lecture_id = ? AND user_id = ?')
    .bind(status, ctx.now, ctx.params.id, ctx.params.userId).run();
  if (!r.meta.changes) throw notFound();
  await adminAudit(ctx, 'registration_status', 'lecture', ctx.params.id!, { userId: ctx.params.userId, status }).run();
  return json({ ok: true });
}
