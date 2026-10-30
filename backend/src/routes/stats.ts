// Census ("الإحصاء"): meetings, registrations and attendance for the two statistics apps.
// Same accounts and sessions as the rest of the API; every permission is checked here.
import { z } from 'zod';
import type { Ctx } from '../env';
import { DZ_GEO } from '../data/dzGeo';
import { adminAudit } from '../lib/audit';
import { b64url, hmacSha256, randomToken, timingSafeEqual, uuid } from '../lib/crypto';
import { badRequest, conflict, forbidden, HttpError, json, notFound, readJson } from '../lib/http';
import { rateLimit } from '../lib/ratelimit';
import { requireUser, roleAtLeast } from '../lib/rbac';

// ───────────── Geography ─────────────

const WILAYAS = new Map(DZ_GEO.w.map(([code, ar, fr]) => [code, { ar, fr }]));
const COMMUNES = new Map(DZ_GEO.c.map(([id, w, ar, fr]) => [id, { w, ar, fr }]));

export const communeInWilaya = (communeId: number, wilayaCode: number) => COMMUNES.get(communeId)?.w === wilayaCode;

// ───────────── Access ─────────────

export type StaffLevel = 'manager' | 'officer';

/**
 * Census administration needs a device activated from the Telegram bot (a long-lived session, see
 * deviceRequests.ts): an e-mail / password sign-in alone never opens it.
 */
export async function staffLevel(ctx: Ctx): Promise<StaffLevel | null> {
  const u = requireUser(ctx);
  const row = await ctx.env.DB.prepare(
    `SELECT s.long_lived, st.level FROM auth_sessions s LEFT JOIN stats_staff st ON st.user_id = s.user_id WHERE s.id = ? AND s.user_id = ?`,
  ).bind(u.sessionId, u.id).first<{ long_lived: number; level: StaffLevel | null }>();
  if (!row?.long_lived) return null;
  if (roleAtLeast(u.role, 'ADMIN')) return 'manager';
  return row.level ?? null;
}

async function requireStaff(ctx: Ctx): Promise<StaffLevel> {
  const level = await staffLevel(ctx);
  if (!level) throw forbidden('not_stats_staff');
  return level;
}

async function requireManager(ctx: Ctx): Promise<void> {
  if ((await requireStaff(ctx)) !== 'manager') throw forbidden('not_stats_manager');
}

export async function access(ctx: Ctx): Promise<Response> {
  return json({ level: await staffLevel(ctx) });
}

// ───────────── Validation ─────────────

const NAME = z.string().trim().min(2).max(60).regex(/^[\p{L}\p{M}' .-]+$/u, 'invalid_name');
const DATE = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'invalid_date');

export function validBirthDate(s: string, now: number): boolean {
  const [y, m, d] = s.split('-').map(Number) as [number, number, number];
  const t = Date.UTC(y, m - 1, d);
  const back = new Date(t);
  return y >= 1900 && back.getUTCFullYear() === y && back.getUTCMonth() === m - 1 && back.getUTCDate() === d && t <= now;
}

const personSchema = z.object({
  firstName: NAME,
  lastName: NAME,
  gender: z.enum(['male', 'female']).nullish(),
  wilayaCode: z.number().int().min(1).max(58),
  communeId: z.number().int().positive(),
  birthDate: DATE,
  maritalStatus: z.enum(['single', 'married', 'divorced', 'widowed']),
});
type Person = z.infer<typeof personSchema>;

function checkPerson(p: Person, now: number) {
  if (!communeInWilaya(p.communeId, p.wilayaCode)) throw badRequest('invalid_commune');
  if (!validBirthDate(p.birthDate, now)) throw badRequest('invalid_birth_date');
}

/** Same person ⇔ same name (ignoring case, Arabic diacritics and letter variants) and birth date. */
export function personKey(first: string, last: string, birthDate: string): string {
  const norm = (s: string) => s.toLowerCase()
    .normalize('NFKD').replace(/[̀-ًͯ-ٰٟـ]/g, '')
    .replace(/[أإآٱ]/g, 'ا').replace(/ة/g, 'ه').replace(/ى/g, 'ي')
    .replace(/[^\p{L}]+/gu, ' ').trim();
  return `${norm(first)}|${norm(last)}|${birthDate}`;
}

// ───────────── QR codes ─────────────

const QR_PREFIX = 'AST1';

async function qrSignature(ctx: Ctx, id: string, nonce: string): Promise<string> {
  return b64url(await hmacSha256(ctx.env.JWT_SECRET, `stats-checkin|${id}|${nonce}`)).slice(0, 27);
}

/** The QR code identifies the registration and proves it was issued by the server; it holds no personal data. */
export async function qrCode(ctx: Ctx, id: string, nonce: string): Promise<string> {
  return `${QR_PREFIX}.${id}.${await qrSignature(ctx, id, nonce)}`;
}

// ───────────── Rows & DTOs ─────────────

interface MeetingRow {
  id: string; title: string; description: string | null; location: string; starts_at: number;
  checkin_opens_at: number; checkin_closes_at: number; late_after: number; registration_open: number;
  status: 'open' | 'closed' | 'cancelled'; closed_at: number | null; created_at: number; updated_at: number;
  c_registered?: number; c_present?: number; c_late?: number; c_absent?: number; c_cancelled?: number;
}

interface RegRow {
  id: string; meeting_id: string; reg_no: number; user_id: string | null; first_name: string; last_name: string;
  gender: string | null; wilaya_code: number; commune_id: number; birth_date: string; marital_status: string;
  person_key: string; status: string; checkin_nonce: string; checked_in_at: number | null; checked_in_by: string | null;
  checkin_method: string | null; source: string; created_at: number; updated_at: number;
  meeting_title?: string; meeting_starts_at?: number; meeting_location?: string; meeting_status?: string; checker_name?: string | null;
}

const COUNTS = `
  (SELECT COUNT(*) FROM stats_registrations r WHERE r.meeting_id = m.id AND r.status = 'registered') AS c_registered,
  (SELECT COUNT(*) FROM stats_registrations r WHERE r.meeting_id = m.id AND r.status = 'present') AS c_present,
  (SELECT COUNT(*) FROM stats_registrations r WHERE r.meeting_id = m.id AND r.status = 'late') AS c_late,
  (SELECT COUNT(*) FROM stats_registrations r WHERE r.meeting_id = m.id AND r.status = 'absent') AS c_absent,
  (SELECT COUNT(*) FROM stats_registrations r WHERE r.meeting_id = m.id AND r.status = 'cancelled') AS c_cancelled`;

const registrationOpen = (m: MeetingRow, now: number) => m.status === 'open' && !!m.registration_open && now < m.checkin_closes_at;

function meetingDto(m: MeetingRow, now: number, withCounts = false) {
  return {
    id: m.id, title: m.title, description: m.description, location: m.location, startsAt: m.starts_at,
    checkinOpensAt: m.checkin_opens_at, checkinClosesAt: m.checkin_closes_at, lateAfter: m.late_after,
    registrationEnabled: !!m.registration_open, registrationOpen: registrationOpen(m, now), status: m.status, closedAt: m.closed_at,
    ...(withCounts ? {
      counts: {
        registered: m.c_registered ?? 0, present: m.c_present ?? 0, late: m.c_late ?? 0, absent: m.c_absent ?? 0, cancelled: m.c_cancelled ?? 0,
        total: (m.c_registered ?? 0) + (m.c_present ?? 0) + (m.c_late ?? 0) + (m.c_absent ?? 0),
      },
    } : {}),
  };
}

function regDto(r: RegRow) {
  const w = WILAYAS.get(r.wilaya_code);
  const c = COMMUNES.get(r.commune_id);
  return {
    id: r.id, meetingId: r.meeting_id, regNo: r.reg_no, firstName: r.first_name, lastName: r.last_name, gender: r.gender,
    wilayaCode: r.wilaya_code, wilayaAr: w?.ar ?? null, wilayaFr: w?.fr ?? null,
    communeId: r.commune_id, communeAr: c?.ar ?? null, communeFr: c?.fr ?? null,
    birthDate: r.birth_date, maritalStatus: r.marital_status, status: r.status,
    checkedInAt: r.checked_in_at, checkinMethod: r.checkin_method, source: r.source, hasAccount: !!r.user_id,
    createdAt: r.created_at, updatedAt: r.updated_at,
    meeting: r.meeting_title !== undefined ? { title: r.meeting_title, startsAt: r.meeting_starts_at, location: r.meeting_location, status: r.meeting_status } : undefined,
  };
}

const REG_SELECT = `SELECT r.*, m.title AS meeting_title, m.starts_at AS meeting_starts_at, m.location AS meeting_location, m.status AS meeting_status,
  u.full_name AS checker_name
  FROM stats_registrations r JOIN stats_meetings m ON m.id = r.meeting_id LEFT JOIN users u ON u.id = r.checked_in_by`;

async function loadMeeting(ctx: Ctx, id: string): Promise<MeetingRow> {
  const m = await ctx.env.DB.prepare('SELECT * FROM stats_meetings WHERE id = ?').bind(id).first<MeetingRow>();
  if (!m) throw notFound();
  return m;
}

async function loadReg(ctx: Ctx, id: string): Promise<RegRow> {
  const r = await ctx.env.DB.prepare(`${REG_SELECT} WHERE r.id = ?`).bind(id).first<RegRow>();
  if (!r) throw notFound();
  return r;
}

// ───────────── Registration (shared by members and officers) ─────────────

/**
 * Inserts a registration, numbering it inside the meeting atomically. A cancelled registration of the
 * same person is reactivated instead; an active one is refused (no double registration).
 */
async function createRegistration(ctx: Ctx, m: MeetingRow, p: Person, userId: string | null, source: 'member' | 'staff'): Promise<RegRow> {
  const db = ctx.env.DB;
  const key = personKey(p.firstName, p.lastName, p.birthDate);
  const existing = await db.prepare(
    `SELECT * FROM stats_registrations WHERE meeting_id = ? AND (person_key = ? OR (? IS NOT NULL AND user_id = ?))`,
  ).bind(m.id, key, userId, userId).all<RegRow>();
  const rows = existing.results ?? [];
  const active = rows.find((r) => r.status !== 'cancelled');
  if (active) throw new HttpError(409, 'already_registered', 'already_registered', { regNo: active.reg_no, ownedByYou: !!userId && active.user_id === userId });
  // Reactivate a cancelled registration of this member (or this person, when no other account owns it).
  const reuse = rows.find((r) => (userId ? r.user_id === userId : !r.user_id)) ?? (rows.length === 1 && !rows[0]!.user_id ? rows[0] : undefined);
  if (rows.length && !reuse) throw conflict('already_registered');
  const nonce = randomToken(18);
  const id = reuse?.id ?? uuid();
  try {
    if (reuse) {
      await db.prepare(
        `UPDATE stats_registrations SET user_id = COALESCE(?, user_id), first_name = ?, last_name = ?, gender = ?, wilaya_code = ?, commune_id = ?, birth_date = ?,
           marital_status = ?, person_key = ?, status = 'registered', checkin_nonce = ?, checked_in_at = NULL, checked_in_by = NULL, checkin_method = NULL,
           source = ?, created_by = ?, updated_at = ? WHERE id = ? AND status = 'cancelled'`,
      ).bind(userId, p.firstName, p.lastName, p.gender ?? null, p.wilayaCode, p.communeId, p.birthDate, p.maritalStatus, key, nonce,
        source, ctx.user!.id, ctx.now, reuse.id).run();
    } else {
      await db.prepare(
        `INSERT INTO stats_registrations (id, meeting_id, reg_no, user_id, first_name, last_name, gender, wilaya_code, commune_id, birth_date,
           marital_status, person_key, status, checkin_nonce, source, created_by, created_at, updated_at)
         SELECT ?1, ?2, (SELECT COALESCE(MAX(reg_no), 0) + 1 FROM stats_registrations WHERE meeting_id = ?2), ?3, ?4, ?5, ?6, ?7, ?8, ?9,
           ?10, ?11, 'registered', ?12, ?13, ?14, ?15, ?15`,
      ).bind(id, m.id, userId, p.firstName, p.lastName, p.gender ?? null, p.wilayaCode, p.communeId, p.birthDate,
        p.maritalStatus, key, nonce, source, ctx.user!.id, ctx.now).run();
    }
  } catch (e) {
    // A concurrent request registered the same person a moment earlier.
    if (String(e).includes('UNIQUE')) throw conflict('already_registered');
    throw e;
  }
  return loadReg(ctx, id);
}

// ───────────── Members ─────────────

interface ProfileRow { first_name: string; last_name: string; gender: string | null; marital_status: string | null; wilaya_code: number | null; commune_id: number | null; birth_date: string | null }

/** The member's census answers, from the profile shared with the lectures form (or the account name). */
export async function myStatsProfile(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  const [p, u] = await Promise.all([
    ctx.env.DB.prepare('SELECT * FROM member_profiles WHERE user_id = ?').bind(me.id).first<ProfileRow>(),
    ctx.env.DB.prepare('SELECT full_name FROM users WHERE id = ?').bind(me.id).first<{ full_name: string }>(),
  ]);
  const [first = '', ...rest] = (u?.full_name ?? '').trim().split(/\s+/);
  return json({
    profile: {
      firstName: p?.first_name ?? first, lastName: p?.last_name ?? rest.join(' '), gender: p?.gender ?? null,
      wilayaCode: p?.wilaya_code ?? null, communeId: p?.commune_id ?? null, birthDate: p?.birth_date ?? null, maritalStatus: p?.marital_status ?? null,
    },
  });
}

function saveProfile(ctx: Ctx, userId: string, p: Person): D1PreparedStatement {
  const wilaya = WILAYAS.get(p.wilayaCode)!.ar;
  return ctx.env.DB.prepare(
    `INSERT INTO member_profiles (user_id, first_name, last_name, gender, birth_year, wilaya, marital_status, wilaya_code, commune_id, birth_date, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(user_id) DO UPDATE SET first_name = excluded.first_name, last_name = excluded.last_name, gender = COALESCE(excluded.gender, gender),
       birth_year = excluded.birth_year, wilaya = excluded.wilaya, marital_status = excluded.marital_status, wilaya_code = excluded.wilaya_code,
       commune_id = excluded.commune_id, birth_date = excluded.birth_date, updated_at = excluded.updated_at`,
  ).bind(userId, p.firstName, p.lastName, p.gender ?? null, Number(p.birthDate.slice(0, 4)), wilaya, p.maritalStatus, p.wilayaCode, p.communeId, p.birthDate, ctx.now);
}

export async function updateMyStatsProfile(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  await rateLimit(ctx.env.DB, `statsprofile:${me.id}`, 30, 3600, ctx.now);
  const p = await readJson(ctx.req, personSchema);
  checkPerson(p, ctx.now);
  await saveProfile(ctx, me.id, p).run();
  return myStatsProfile(ctx);
}

/** Meetings a member can still register for or attend, with their own registration if any. */
export async function listOpenMeetings(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  const { results } = await ctx.env.DB.prepare(
    `SELECT m.*, r.id AS my_id, r.status AS my_status, r.reg_no AS my_reg_no FROM stats_meetings m
     LEFT JOIN stats_registrations r ON r.meeting_id = m.id AND r.user_id = ?
     WHERE m.status = 'open' AND m.checkin_closes_at > ? ORDER BY m.starts_at LIMIT 100`,
  ).bind(me.id, ctx.now).all<MeetingRow & { my_id: string | null; my_status: string | null; my_reg_no: number | null }>();
  return json({
    items: (results ?? []).map((m) => ({
      ...meetingDto(m, ctx.now),
      myRegistration: m.my_id ? { id: m.my_id, status: m.my_status, regNo: m.my_reg_no } : null,
    })),
  });
}

export async function registerSelf(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  await rateLimit(ctx.env.DB, `statsreg:${me.id}`, 20, 3600, ctx.now);
  const p = await readJson(ctx.req, personSchema);
  checkPerson(p, ctx.now);
  const m = await loadMeeting(ctx, ctx.params.id!);
  if (!registrationOpen(m, ctx.now)) throw new HttpError(409, 'registration_closed');
  const r = await createRegistration(ctx, m, p, me.id, 'member');
  await saveProfile(ctx, me.id, p).run();
  return json({ item: { ...regDto(r), qr: await qrCode(ctx, r.id, r.checkin_nonce) } }, 201);
}

export async function myRegistrations(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  const { results } = await ctx.env.DB.prepare(`${REG_SELECT} WHERE r.user_id = ? ORDER BY m.starts_at DESC LIMIT 200`).bind(me.id).all<RegRow>();
  return json({ items: (results ?? []).map(regDto) });
}

async function ownReg(ctx: Ctx): Promise<RegRow> {
  const me = requireUser(ctx);
  const r = await loadReg(ctx, ctx.params.id!);
  // Another member's registration is reported as missing, not as forbidden.
  if (r.user_id !== me.id) throw notFound();
  return r;
}

/** The participation card: own registration only, with the QR code (not while cancelled). */
export async function myRegistration(ctx: Ctx): Promise<Response> {
  const r = await ownReg(ctx);
  return json({ item: { ...regDto(r), qr: r.status === 'cancelled' ? null : await qrCode(ctx, r.id, r.checkin_nonce) } });
}

export async function cancelMyRegistration(ctx: Ctx): Promise<Response> {
  const r = await ownReg(ctx);
  const res = await ctx.env.DB.prepare(
    `UPDATE stats_registrations SET status = 'cancelled', updated_at = ? WHERE id = ? AND status = 'registered'
       AND (SELECT status FROM stats_meetings WHERE id = meeting_id) = 'open'`,
  ).bind(ctx.now, r.id).run();
  if (!res.meta.changes) throw conflict('cannot_cancel');
  return json({ item: regDto(await loadReg(ctx, r.id)) });
}

/** A change counter the apps poll (every few seconds while open) to refresh as soon as data changes. */
export async function sync(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  const level = await staffLevel(ctx);
  const db = ctx.env.DB;
  const regs = level
    ? db.prepare('SELECT MAX(updated_at) AS v, COUNT(*) AS n FROM stats_registrations')
    : db.prepare('SELECT MAX(updated_at) AS v, COUNT(*) AS n FROM stats_registrations WHERE user_id = ?').bind(me.id);
  const [r, m] = await Promise.all([
    regs.first<{ v: number | null; n: number }>(),
    db.prepare('SELECT MAX(updated_at) AS v, COUNT(*) AS n FROM stats_meetings').first<{ v: number | null; n: number }>(),
  ]);
  return json({ version: `${r?.v ?? 0}.${r?.n ?? 0}.${m?.v ?? 0}.${m?.n ?? 0}`, level });
}

// ───────────── Meetings (managers) ─────────────

const MIN = 60_000;
const meetingFields = {
  title: z.string().trim().min(3).max(200),
  description: z.string().trim().max(2000).nullish(),
  location: z.string().trim().min(2).max(300),
  startsAt: z.number().int().positive(),
  checkinOpensAt: z.number().int().positive().optional(),
  checkinClosesAt: z.number().int().positive().optional(),
  lateAfter: z.number().int().positive().optional(),
  registrationOpen: z.boolean().default(true),
};
const createMeetingSchema = z.object(meetingFields).strict();
const updateMeetingSchema = z.object({
  ...meetingFields,
  registrationOpen: z.boolean().optional(),
  status: z.enum(['open', 'cancelled']).optional(),
}).partial().strict();

function checkTimes(t: { startsAt: number; opens: number; closes: number; late: number }) {
  if (t.closes <= t.opens) throw badRequest('invalid_checkin_window');
  if (t.late < t.opens || t.late > t.closes) throw badRequest('invalid_late_after');
}

export async function adminListMeetings(ctx: Ctx): Promise<Response> {
  await requireStaff(ctx);
  const { results } = await ctx.env.DB.prepare(`SELECT m.*, ${COUNTS} FROM stats_meetings m ORDER BY m.starts_at DESC LIMIT 200`).all<MeetingRow>();
  return json({ items: (results ?? []).map((m) => meetingDto(m, ctx.now, true)) });
}

export async function adminGetMeeting(ctx: Ctx): Promise<Response> {
  await requireStaff(ctx);
  const m = await ctx.env.DB.prepare(`SELECT m.*, ${COUNTS} FROM stats_meetings m WHERE m.id = ?`).bind(ctx.params.id).first<MeetingRow>();
  if (!m) throw notFound();
  return json({ item: meetingDto(m, ctx.now, true) });
}

export async function adminCreateMeeting(ctx: Ctx): Promise<Response> {
  await requireManager(ctx);
  const b = await readJson(ctx.req, createMeetingSchema);
  // Defaults: confirmation opens 1 h before, closes 3 h after the start; late after 15 minutes.
  const opens = b.checkinOpensAt ?? b.startsAt - 60 * MIN;
  const closes = b.checkinClosesAt ?? b.startsAt + 180 * MIN;
  const late = b.lateAfter ?? b.startsAt + 15 * MIN;
  checkTimes({ startsAt: b.startsAt, opens, closes, late });
  const id = uuid();
  await ctx.env.DB.batch([
    ctx.env.DB.prepare(
      `INSERT INTO stats_meetings (id, title, description, location, starts_at, checkin_opens_at, checkin_closes_at, late_after, registration_open,
         status, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', ?, ?, ?)`,
    ).bind(id, b.title, b.description ?? null, b.location, b.startsAt, opens, closes, late, b.registrationOpen ? 1 : 0, ctx.user!.id, ctx.now, ctx.now),
    adminAudit(ctx, 'stats.meeting.create', 'stats_meeting', id, { title: b.title, startsAt: b.startsAt }),
  ]);
  return json({ id }, 201);
}

export async function adminUpdateMeeting(ctx: Ctx): Promise<Response> {
  await requireManager(ctx);
  const b = await readJson(ctx.req, updateMeetingSchema);
  const m = await loadMeeting(ctx, ctx.params.id!);
  if (m.status === 'closed') throw conflict('meeting_closed');
  const next = {
    title: b.title ?? m.title, description: b.description === undefined ? m.description : b.description ?? null, location: b.location ?? m.location,
    startsAt: b.startsAt ?? m.starts_at, opens: b.checkinOpensAt ?? m.checkin_opens_at, closes: b.checkinClosesAt ?? m.checkin_closes_at,
    late: b.lateAfter ?? m.late_after, open: b.registrationOpen ?? !!m.registration_open, status: b.status ?? m.status,
  };
  checkTimes(next);
  await ctx.env.DB.batch([
    ctx.env.DB.prepare(
      `UPDATE stats_meetings SET title = ?, description = ?, location = ?, starts_at = ?, checkin_opens_at = ?, checkin_closes_at = ?, late_after = ?,
         registration_open = ?, status = ?, updated_at = ? WHERE id = ?`,
    ).bind(next.title, next.description, next.location, next.startsAt, next.opens, next.closes, next.late, next.open ? 1 : 0, next.status, ctx.now, m.id),
    adminAudit(ctx, 'stats.meeting.update', 'stats_meeting', m.id, b),
  ]);
  return adminGetMeeting(ctx);
}

/** Closes the meeting: registration stops, everyone not confirmed becomes "absent", and the final report is returned. */
export async function adminCloseMeeting(ctx: Ctx): Promise<Response> {
  await requireManager(ctx);
  const m = await loadMeeting(ctx, ctx.params.id!);
  if (m.status !== 'open') throw conflict('meeting_not_open');
  await ctx.env.DB.batch([
    ctx.env.DB.prepare(`UPDATE stats_meetings SET status = 'closed', registration_open = 0, closed_at = ?, closed_by = ?, updated_at = ? WHERE id = ?`)
      .bind(ctx.now, ctx.user!.id, ctx.now, m.id),
    ctx.env.DB.prepare(`UPDATE stats_registrations SET status = 'absent', updated_at = ? WHERE meeting_id = ? AND status = 'registered'`).bind(ctx.now, m.id),
    adminAudit(ctx, 'stats.meeting.close', 'stats_meeting', m.id, null),
  ]);
  return json({ report: await buildStats(ctx, m.id) });
}

// ───────────── Dashboard ─────────────

const AGE_GROUPS: [string, number, number][] = [['0-17', 0, 17], ['18-25', 18, 25], ['26-35', 26, 35], ['36-45', 36, 45], ['46-60', 46, 60], ['61+', 61, 200]];

export function ageOn(birthDate: string, at: number): number {
  const d = new Date(at);
  const [y, m, day] = birthDate.split('-').map(Number) as [number, number, number];
  let age = d.getUTCFullYear() - y;
  if (d.getUTCMonth() + 1 < m || (d.getUTCMonth() + 1 === m && d.getUTCDate() < day)) age--;
  return age;
}

async function buildStats(ctx: Ctx, meetingId: string | null) {
  const db = ctx.env.DB;
  const where = meetingId ? `r.status <> 'cancelled' AND r.meeting_id = ?` : `r.status <> 'cancelled'`;
  const q = <T>(sql: string) => {
    const st = db.prepare(sql);
    return (meetingId ? st.bind(meetingId) : st).all<T>().then((x) => x.results ?? []);
  };
  const [status, gender, wilaya, commune, marital, births, started] = await Promise.all([
    q<{ k: string; n: number }>(`SELECT r.status AS k, COUNT(*) AS n FROM stats_registrations r WHERE ${meetingId ? 'r.meeting_id = ?' : '1 = 1'} GROUP BY r.status`),
    q<{ k: string | null; n: number }>(`SELECT r.gender AS k, COUNT(*) AS n FROM stats_registrations r WHERE ${where} GROUP BY r.gender`),
    q<{ k: number; n: number; a: number }>(`SELECT r.wilaya_code AS k, COUNT(*) AS n, SUM(r.status IN ('present','late')) AS a FROM stats_registrations r WHERE ${where} GROUP BY r.wilaya_code ORDER BY n DESC`),
    q<{ k: number; n: number; a: number }>(`SELECT r.commune_id AS k, COUNT(*) AS n, SUM(r.status IN ('present','late')) AS a FROM stats_registrations r WHERE ${where} GROUP BY r.commune_id ORDER BY n DESC LIMIT 30`),
    q<{ k: string; n: number }>(`SELECT r.marital_status AS k, COUNT(*) AS n FROM stats_registrations r WHERE ${where} GROUP BY r.marital_status`),
    q<{ k: string; n: number }>(`SELECT r.birth_date AS k, COUNT(*) AS n FROM stats_registrations r WHERE ${where} GROUP BY r.birth_date`),
    // Attendance rate only counts meetings whose confirmation period has started.
    q<{ total: number; attended: number }>(
      `SELECT COUNT(*) AS total, COALESCE(SUM(r.status IN ('present','late')), 0) AS attended FROM stats_registrations r
       JOIN stats_meetings m ON m.id = r.meeting_id WHERE ${where} AND m.checkin_opens_at <= ${Math.floor(ctx.now)}`,
    ),
  ]);
  const by = Object.fromEntries(status.map((s) => [s.k, s.n])) as Record<string, number>;
  const ages = AGE_GROUPS.map(([label]) => ({ key: label, count: 0 }));
  for (const b of births) {
    const a = ageOn(b.k, ctx.now);
    const i = AGE_GROUPS.findIndex(([, lo, hi]) => a >= lo && a <= hi);
    if (i >= 0) ages[i]!.count += b.n;
  }
  const s = started[0] ?? { total: 0, attended: 0 };
  return {
    meetingId,
    totals: {
      registrations: (by.registered ?? 0) + (by.present ?? 0) + (by.late ?? 0) + (by.absent ?? 0),
      registered: by.registered ?? 0, present: by.present ?? 0, late: by.late ?? 0, absent: by.absent ?? 0, cancelled: by.cancelled ?? 0,
      attended: (by.present ?? 0) + (by.late ?? 0),
    },
    attendanceRate: s.total ? s.attended / s.total : null,
    gender: { male: gender.find((g) => g.k === 'male')?.n ?? 0, female: gender.find((g) => g.k === 'female')?.n ?? 0, unknown: gender.find((g) => g.k === null)?.n ?? 0 },
    byWilaya: wilaya.map((w) => ({ code: w.k, ar: WILAYAS.get(w.k)?.ar, fr: WILAYAS.get(w.k)?.fr, count: w.n, attended: w.a ?? 0 })),
    byCommune: commune.map((c) => ({ id: c.k, ar: COMMUNES.get(c.k)?.ar, fr: COMMUNES.get(c.k)?.fr, wilayaCode: COMMUNES.get(c.k)?.w, count: c.n, attended: c.a ?? 0 })),
    ageGroups: ages,
    maritalStatus: ['single', 'married', 'divorced', 'widowed'].map((k) => ({ key: k, count: marital.find((m) => m.k === k)?.n ?? 0 })),
  };
}

export async function adminDashboard(ctx: Ctx): Promise<Response> {
  await requireStaff(ctx);
  const meetingId = ctx.url.searchParams.get('meetingId');
  if (meetingId) await loadMeeting(ctx, meetingId);
  const { results } = await ctx.env.DB.prepare(`SELECT m.*, ${COUNTS} FROM stats_meetings m WHERE m.status <> 'cancelled' ORDER BY m.starts_at DESC LIMIT 50`).all<MeetingRow>();
  return json({ stats: await buildStats(ctx, meetingId), meetings: (results ?? []).map((m) => meetingDto(m, ctx.now, true)), generatedAt: ctx.now });
}

// ───────────── Registrations (officers & managers) ─────────────

export async function adminRegister(ctx: Ctx): Promise<Response> {
  await requireStaff(ctx);
  await rateLimit(ctx.env.DB, `statsstaffreg:${ctx.user!.id}`, 600, 3600, ctx.now);
  const b = await readJson(ctx.req, personSchema.extend({ meetingId: z.string().uuid() }));
  checkPerson(b, ctx.now);
  const m = await loadMeeting(ctx, b.meetingId);
  if (m.status !== 'open') throw conflict('meeting_not_open');
  const r = await createRegistration(ctx, m, b, null, 'staff');
  await adminAudit(ctx, 'stats.registration.create', 'stats_registration', r.id, { meetingId: m.id, regNo: r.reg_no }).run();
  return json({ item: regDto(r) }, 201);
}

const escapeLike = (s: string) => s.replace(/[\\%_]/g, (c) => `\\${c}`);

export async function adminListRegistrations(ctx: Ctx): Promise<Response> {
  const level = await requireStaff(ctx);
  const p = ctx.url.searchParams;
  const all = p.get('all') === '1';
  // The complete list (for PDF / Excel / CSV export) carries personal data: managers only.
  if (all && level !== 'manager') throw forbidden('not_stats_manager');
  const page = Math.max(1, parseInt(p.get('page') ?? '1', 10) || 1);
  const size = all ? 5000 : Math.min(200, Math.max(1, parseInt(p.get('pageSize') ?? '50', 10) || 50));
  const where: string[] = [];
  const args: unknown[] = [];
  const meetingId = p.get('meetingId');
  if (meetingId) { where.push('r.meeting_id = ?'); args.push(meetingId); }
  const wilaya = parseInt(p.get('wilaya') ?? '', 10);
  if (wilaya) { where.push('r.wilaya_code = ?'); args.push(wilaya); }
  const commune = parseInt(p.get('commune') ?? '', 10);
  if (commune) { where.push('r.commune_id = ?'); args.push(commune); }
  const status = p.get('status');
  if (status) {
    if (status === 'attended') where.push(`r.status IN ('present','late')`);
    else if (['registered', 'present', 'late', 'absent', 'cancelled'].includes(status)) { where.push('r.status = ?'); args.push(status); }
  }
  const text = (p.get('q') ?? '').trim().slice(0, 80);
  if (/^\d+$/.test(text)) { where.push('r.reg_no = ?'); args.push(parseInt(text, 10)); }
  else if (text) {
    where.push(`((r.first_name || ' ' || r.last_name) LIKE ? ESCAPE '\\' OR (r.last_name || ' ' || r.first_name) LIKE ? ESCAPE '\\')`);
    const like = `%${escapeLike(text)}%`;
    args.push(like, like);
  }
  const sqlWhere = where.length ? `WHERE ${where.join(' AND ')}` : '';
  const [rows, count] = await Promise.all([
    ctx.env.DB.prepare(`${REG_SELECT} ${sqlWhere} ORDER BY m.starts_at DESC, r.reg_no ASC LIMIT ? OFFSET ?`).bind(...args, size + 1, (page - 1) * size).all<RegRow>(),
    ctx.env.DB.prepare(`SELECT COUNT(*) AS n FROM stats_registrations r ${sqlWhere}`).bind(...args).first<{ n: number }>(),
  ]);
  const items = rows.results ?? [];
  if (all) await adminAudit(ctx, 'stats.export', 'stats_registration', null, { filters: Object.fromEntries(p), rows: Math.min(items.length, size) }).run();
  return json({
    items: items.slice(0, size).map((r) => ({ ...regDto(r), checkedInByName: r.checker_name ?? null })),
    hasMore: items.length > size, total: count?.n ?? 0, page,
  });
}

export async function adminGetRegistration(ctx: Ctx): Promise<Response> {
  await requireStaff(ctx);
  const r = await loadReg(ctx, ctx.params.id!);
  return json({ item: { ...regDto(r), checkedInByName: r.checker_name ?? null } });
}

const correctionSchema = personSchema.partial().extend({
  status: z.enum(['registered', 'present', 'late', 'absent', 'cancelled']).optional(),
}).strict();

/** Corrections by a manager; the before/after values go to the audit log. */
export async function adminUpdateRegistration(ctx: Ctx): Promise<Response> {
  await requireManager(ctx);
  const b = await readJson(ctx.req, correctionSchema);
  const r = await loadReg(ctx, ctx.params.id!);
  const p: Person = {
    firstName: b.firstName ?? r.first_name, lastName: b.lastName ?? r.last_name, gender: b.gender === undefined ? (r.gender as Person['gender']) : b.gender,
    wilayaCode: b.wilayaCode ?? r.wilaya_code, communeId: b.communeId ?? r.commune_id, birthDate: b.birthDate ?? r.birth_date,
    maritalStatus: (b.maritalStatus ?? r.marital_status) as Person['maritalStatus'],
  };
  checkPerson(p, ctx.now);
  const status = b.status ?? r.status;
  const attended = status === 'present' || status === 'late';
  const wasAttended = r.status === 'present' || r.status === 'late';
  const key = personKey(p.firstName, p.lastName, p.birthDate);
  const before = regDto(r);
  try {
    await ctx.env.DB.batch([
      ctx.env.DB.prepare(
        `UPDATE stats_registrations SET first_name = ?, last_name = ?, gender = ?, wilaya_code = ?, commune_id = ?, birth_date = ?, marital_status = ?,
           person_key = ?, status = ?, checked_in_at = ?, checked_in_by = ?, checkin_method = ?, updated_at = ? WHERE id = ?`,
      ).bind(p.firstName, p.lastName, p.gender ?? null, p.wilayaCode, p.communeId, p.birthDate, p.maritalStatus, key, status,
        attended ? (wasAttended ? r.checked_in_at : ctx.now) : null,
        attended ? (wasAttended ? r.checked_in_by : ctx.user!.id) : null,
        attended ? (wasAttended ? r.checkin_method : 'manual') : null, ctx.now, r.id),
      adminAudit(ctx, 'stats.registration.update', 'stats_registration', r.id, { before, changes: b }),
    ]);
  } catch (e) {
    if (String(e).includes('UNIQUE')) throw conflict('already_registered');
    throw e;
  }
  return adminGetRegistration(ctx);
}

// ───────────── Attendance ─────────────

const checkinSchema = z.object({
  meetingId: z.string().uuid(),
  code: z.string().trim().max(200).optional(),
  registrationId: z.string().uuid().optional(),
  regNo: z.number().int().positive().optional(),
}).refine((b) => [b.code, b.registrationId, b.regNo].filter((x) => x !== undefined).length === 1, 'one_identifier');

/**
 * Confirms attendance by QR code, registration number or a manual pick from the list. The update only
 * succeeds from "registered", so the same person can never be confirmed twice.
 */
export async function adminCheckin(ctx: Ctx): Promise<Response> {
  await requireStaff(ctx);
  await rateLimit(ctx.env.DB, `statscheckin:${ctx.user!.id}`, 1200, 3600, ctx.now);
  const b = await readJson(ctx.req, checkinSchema);
  const m = await loadMeeting(ctx, b.meetingId);
  let r: RegRow | null = null;
  let method: 'qr' | 'manual' | 'number';
  if (b.code !== undefined) {
    method = 'qr';
    const [prefix, id, sig] = b.code.split('.');
    if (prefix !== QR_PREFIX || !id || !sig || !/^[0-9a-f-]{36}$/.test(id)) throw badRequest('invalid_qr');
    r = await ctx.env.DB.prepare(`${REG_SELECT} WHERE r.id = ?`).bind(id).first<RegRow>();
    if (!r || !timingSafeEqual(sig, await qrSignature(ctx, r.id, r.checkin_nonce))) throw badRequest('invalid_qr');
  } else if (b.regNo !== undefined) {
    method = 'number';
    r = await ctx.env.DB.prepare(`${REG_SELECT} WHERE r.meeting_id = ? AND r.reg_no = ?`).bind(m.id, b.regNo).first<RegRow>();
  } else {
    method = 'manual';
    r = await ctx.env.DB.prepare(`${REG_SELECT} WHERE r.id = ?`).bind(b.registrationId).first<RegRow>();
  }
  if (!r) throw notFound('registration_not_found');
  if (r.meeting_id !== m.id) throw new HttpError(409, 'wrong_meeting', 'wrong_meeting', { meetingTitle: r.meeting_title });
  if (m.status !== 'open') throw conflict('meeting_not_open');
  if (r.status === 'cancelled') throw conflict('registration_cancelled');
  if (r.status === 'present' || r.status === 'late') {
    throw new HttpError(409, 'already_checked_in', 'already_checked_in', { at: r.checked_in_at, by: r.checker_name ?? null, item: regDto(r) });
  }
  if (ctx.now < m.checkin_opens_at) throw new HttpError(409, 'checkin_not_open', 'checkin_not_open', { opensAt: m.checkin_opens_at });
  if (ctx.now > m.checkin_closes_at) throw conflict('checkin_closed');
  const status = ctx.now > m.late_after ? 'late' : 'present';
  const res = await ctx.env.DB.prepare(
    `UPDATE stats_registrations SET status = ?, checked_in_at = ?, checked_in_by = ?, checkin_method = ?, updated_at = ? WHERE id = ? AND status = 'registered'`,
  ).bind(status, ctx.now, ctx.user!.id, method, ctx.now, r.id).run();
  if (!res.meta.changes) throw conflict('already_checked_in');
  const updated = await loadReg(ctx, r.id);
  return json({ item: { ...regDto(updated), checkedInByName: updated.checker_name ?? null } });
}

// ───────────── Staff & audit ─────────────

export async function adminListStaff(ctx: Ctx): Promise<Response> {
  const level = await requireStaff(ctx);
  const { results } = await ctx.env.DB.prepare(
    `SELECT u.id, u.full_name, u.email, u.role, s.level, s.created_at FROM stats_staff s JOIN users u ON u.id = s.user_id
     WHERE u.status = 'active' ORDER BY s.created_at DESC`,
  ).all<{ id: string; full_name: string; email: string; role: string; level: StaffLevel; created_at: number }>();
  return json({
    items: (results ?? []).map((s) => ({ userId: s.id, fullName: s.full_name, email: s.email, role: s.role, level: s.level, grantedAt: s.created_at })),
    canManage: level === 'manager',
  });
}

/**
 * Removes someone from the census team (managers only, never themselves). Their census devices are
 * disconnected at once; a census-only account (created by the bot approval) is closed.
 */
export async function adminRevokeStaff(ctx: Ctx): Promise<Response> {
  await requireManager(ctx);
  const target = ctx.params.userId!;
  if (target === ctx.user!.id) throw conflict('cannot_remove_self');
  const db = ctx.env.DB;
  const res = await db.batch([
    db.prepare('DELETE FROM stats_staff WHERE user_id = ?').bind(target),
    db.prepare(`UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL AND (SELECT is_staff_only FROM users WHERE id = ?) = 1`).bind(ctx.now, target, target),
    db.prepare(`UPDATE users SET status = 'suspended', updated_at = ? WHERE id = ? AND is_staff_only = 1 AND role = 'USER'`).bind(ctx.now, target),
    adminAudit(ctx, 'stats.staff.revoke', 'stats_staff', target, null),
  ]);
  if (!res[0]?.meta.changes) throw notFound();
  return json({ ok: true });
}

/** The association's accounts (managers only): who is a member, how to reach them, census participation. */
export async function adminListUsers(ctx: Ctx): Promise<Response> {
  await requireManager(ctx);
  const p = ctx.url.searchParams;
  const page = Math.max(1, parseInt(p.get('page') ?? '1', 10) || 1);
  const size = Math.min(100, Math.max(1, parseInt(p.get('pageSize') ?? '50', 10) || 50));
  const where = [`u.status <> 'deleted'`];
  const args: unknown[] = [];
  const text = (p.get('q') ?? '').trim().slice(0, 80);
  if (text) {
    where.push(`(u.full_name LIKE ? ESCAPE '\\' OR u.email LIKE ? ESCAPE '\\' OR u.phone_e164 LIKE ? ESCAPE '\\')`);
    const like = `%${escapeLike(text)}%`;
    args.push(like, like, like);
  }
  const role = p.get('role');
  if (role && ['SUPER_ADMIN', 'ADMIN', 'EDITOR', 'VOLUNTEER', 'USER'].includes(role)) { where.push('u.role = ?'); args.push(role); }
  // Everyone by default: members (any app or the website), supervisors' devices, census team devices.
  const kind = p.get('kind');
  if (kind === 'members') where.push('u.is_staff_only = 0');
  else if (kind === 'supervisors') where.push('u.is_staff_only = 1 AND NOT EXISTS (SELECT 1 FROM stats_staff st WHERE st.user_id = u.id)');
  else if (kind === 'census') where.push('u.is_staff_only = 1 AND EXISTS (SELECT 1 FROM stats_staff st WHERE st.user_id = u.id)');
  const sqlWhere = `WHERE ${where.join(' AND ')}`;
  const [rows, count] = await Promise.all([
    ctx.env.DB.prepare(
      `SELECT u.id, u.full_name, u.email, u.phone_e164, u.role, u.status, u.email_verified_at, u.phone_verified_at, u.is_staff_only, u.created_at,
         (SELECT COUNT(*) FROM stats_registrations r WHERE r.user_id = u.id AND r.status <> 'cancelled') AS regs,
         (SELECT COUNT(*) FROM stats_registrations r WHERE r.user_id = u.id AND r.status IN ('present','late')) AS attended,
         (SELECT MAX(s.last_used_at) FROM auth_sessions s WHERE s.user_id = u.id) AS last_seen,
         (SELECT level FROM stats_staff st WHERE st.user_id = u.id) AS stats_level,
         p.wilaya_code, p.commune_id, p.birth_date, p.marital_status, p.gender
       FROM users u LEFT JOIN member_profiles p ON p.user_id = u.id ${sqlWhere} ORDER BY u.created_at DESC LIMIT ? OFFSET ?`,
    ).bind(...args, size + 1, (page - 1) * size).all<{
      id: string; full_name: string; email: string; phone_e164: string; role: string; status: string; email_verified_at: number | null;
      phone_verified_at: number | null; is_staff_only: number; created_at: number; regs: number; attended: number;
      wilaya_code: number | null; commune_id: number | null; birth_date: string | null; marital_status: string | null; gender: string | null;
      last_seen: number | null; stats_level: string | null;
    }>(),
    ctx.env.DB.prepare(`SELECT COUNT(*) AS n FROM users u ${sqlWhere}`).bind(...args).first<{ n: number }>(),
  ]);
  const items = rows.results ?? [];
  if (page === 1 && !text) await adminAudit(ctx, 'stats.users.view', 'user', null, { role: role ?? null }).run();
  return json({
    items: items.slice(0, size).map((u) => ({
      id: u.id, fullName: u.full_name, email: u.is_staff_only ? null : u.email, phone: u.is_staff_only ? null : u.phone_e164, role: u.role,
      status: u.status, emailVerified: !!u.email_verified_at, phoneVerified: !!u.phone_verified_at, staffOnly: !!u.is_staff_only, createdAt: u.created_at,
      // member: an association account (members' app, census app, website); supervisor / census: a bot-activated device account.
      kind: !u.is_staff_only ? 'member' : u.stats_level ? 'census' : 'supervisor', statsLevel: u.stats_level, lastSeen: u.last_seen,
      registrations: u.regs, attended: u.attended, gender: u.gender, birthDate: u.birth_date, maritalStatus: u.marital_status,
      wilayaCode: u.wilaya_code, wilayaAr: u.wilaya_code ? WILAYAS.get(u.wilaya_code)?.ar ?? null : null,
      communeAr: u.commune_id ? COMMUNES.get(u.commune_id)?.ar ?? null : null,
    })),
    hasMore: items.length > size, total: count?.n ?? 0, page,
  });
}

export async function adminStatsAudit(ctx: Ctx): Promise<Response> {
  await requireManager(ctx);
  const { results } = await ctx.env.DB.prepare(
    `SELECT a.id, a.action, a.entity_type, a.entity_id, a.detail, a.created_at, u.full_name AS actor
     FROM admin_audit a LEFT JOIN users u ON u.id = a.actor_id WHERE a.action LIKE 'stats.%' ORDER BY a.created_at DESC LIMIT 200`,
  ).all<{ id: number; action: string; entity_type: string; entity_id: string | null; detail: string | null; created_at: number; actor: string | null }>();
  return json({
    items: (results ?? []).map((a) => ({ id: a.id, action: a.action, entityType: a.entity_type, entityId: a.entity_id, detail: a.detail, at: a.created_at, actor: a.actor })),
  });
}
