import { z } from 'zod';
import type { Ctx } from '../env';
import { badRequest, conflict, forbidden, HttpError, json, notFound, readJson } from '../lib/http';
import { randomToken, uuid } from '../lib/crypto';
import { rateLimit } from '../lib/ratelimit';
import { requireRole, requireUser, roleAtLeast } from '../lib/rbac';
import { adminAudit } from '../lib/audit';
import { normalizeAlgerianPhone } from '../lib/phone';
import { queueNotification } from './notifications';

interface PollRow {
  id: string; title_ar: string; title_fr: string | null; description_ar: string | null; description_fr: string | null;
  starts_at: number; ends_at: number; eligibility: 'verified' | 'members'; result_visibility: 'live' | 'after_close' | 'admins';
  anonymous: number; status: string; created_at: number;
}

function pollState(p: PollRow, now: number): 'upcoming' | 'active' | 'closed' {
  if (now < p.starts_at) return 'upcoming';
  if (now >= p.ends_at) return 'closed';
  return 'active';
}

function resultsVisible(p: PollRow, now: number, isAdmin: boolean): boolean {
  if (isAdmin) return true;
  if (p.result_visibility === 'live') return true;
  if (p.result_visibility === 'after_close') return now >= p.ends_at;
  return false;
}

function pollDto(p: PollRow, now: number) {
  return {
    id: p.id,
    titleAr: p.title_ar,
    titleFr: p.title_fr,
    descriptionAr: p.description_ar,
    descriptionFr: p.description_fr,
    startsAt: p.starts_at,
    endsAt: p.ends_at,
    eligibility: p.eligibility,
    resultVisibility: p.result_visibility,
    anonymous: !!p.anonymous,
    status: p.status,
    state: pollState(p, now),
  };
}

async function results(db: D1Database, pollId: string) {
  const [counts, total] = await Promise.all([
    db.prepare(`SELECT option_id, COUNT(*) AS votes FROM votes WHERE poll_id = ? GROUP BY option_id`).bind(pollId).all<{ option_id: string; votes: number }>(),
    db.prepare(`SELECT COUNT(*) AS n FROM poll_participation WHERE poll_id = ?`).bind(pollId).first<{ n: number }>(),
  ]);
  return { totalVotes: total?.n ?? 0, byOption: Object.fromEntries(counts.results.map((r) => [r.option_id, r.votes])) };
}

async function eligibility(db: D1Database, p: PollRow, userId: string | undefined): Promise<{ eligible: boolean; reason: string | null }> {
  if (!userId) return { eligible: false, reason: 'login_required' };
  const u = await db.prepare(`SELECT phone_verified_at, status FROM users WHERE id = ?`).bind(userId).first<{ phone_verified_at: number | null; status: string }>();
  if (!u || u.status !== 'active') return { eligible: false, reason: 'account_inactive' };
  if (!u.phone_verified_at) return { eligible: false, reason: 'phone_not_verified' };
  if (p.eligibility === 'members') {
    const m = await db.prepare('SELECT 1 AS x FROM poll_eligible_members WHERE poll_id = ? AND user_id = ?').bind(p.id, userId).first();
    if (!m) return { eligible: false, reason: 'not_on_member_list' };
  }
  return { eligible: true, reason: null };
}

export async function listPolls(ctx: Ctx): Promise<Response> {
  const state = ctx.url.searchParams.get('state') ?? 'active';
  let where = '';
  if (state === 'active') where = 'AND starts_at <= ?1 AND ends_at > ?1';
  else if (state === 'upcoming') where = 'AND starts_at > ?1';
  else if (state === 'closed') where = 'AND ends_at <= ?1';
  else throw badRequest('invalid_state');
  const rows = await ctx.env.DB.prepare(
    `SELECT * FROM polls WHERE status = 'published' ${where} ORDER BY ${state === 'closed' ? 'ends_at DESC' : 'ends_at ASC'} LIMIT 50`,
  ).bind(ctx.now).all<PollRow>();
  let voted = new Set<string>();
  if (ctx.user && rows.results.length) {
    const ids = rows.results.map((r) => r.id);
    const v = await ctx.env.DB.prepare(
      `SELECT poll_id FROM poll_participation WHERE user_id = ? AND poll_id IN (${ids.map(() => '?').join(',')})`,
    ).bind(ctx.user.id, ...ids).all<{ poll_id: string }>();
    voted = new Set(v.results.map((r) => r.poll_id));
  }
  return json({ items: rows.results.map((p) => ({ ...pollDto(p, ctx.now), hasVoted: voted.has(p.id) })) });
}

export async function getPoll(ctx: Ctx): Promise<Response> {
  const p = await ctx.env.DB.prepare('SELECT * FROM polls WHERE id = ?').bind(ctx.params.id).first<PollRow>();
  const isAdmin = !!ctx.user && roleAtLeast(ctx.user.role, 'ADMIN');
  if (!p || (p.status !== 'published' && !isAdmin)) throw notFound();
  const options = await ctx.env.DB.prepare(
    'SELECT id, label_ar, label_fr, description, image_url FROM poll_options WHERE poll_id = ? ORDER BY position, id',
  ).bind(p.id).all<{ id: string; label_ar: string; label_fr: string | null; description: string | null; image_url: string | null }>();
  const [elig, part] = await Promise.all([
    eligibility(ctx.env.DB, p, ctx.user?.id),
    ctx.user ? ctx.env.DB.prepare('SELECT receipt, voted_at FROM poll_participation WHERE poll_id = ? AND user_id = ?').bind(p.id, ctx.user.id).first<{ receipt: string; voted_at: number }>() : null,
  ]);
  const showResults = resultsVisible(p, ctx.now, isAdmin);
  return json({
    poll: pollDto(p, ctx.now),
    options: options.results.map((o) => ({ id: o.id, labelAr: o.label_ar, labelFr: o.label_fr, description: o.description, imageUrl: o.image_url })),
    eligible: elig.eligible,
    ineligibleReason: elig.reason,
    hasVoted: !!part,
    receipt: part?.receipt ?? null,
    votedAt: part?.voted_at ?? null,
    results: showResults ? await results(ctx.env.DB, p.id) : null,
  });
}

const voteSchema = z.object({ optionId: z.string().uuid() });

export async function castVote(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  await rateLimit(ctx.env.DB, `vote:${me.id}`, 10, 60, ctx.now);
  const { optionId } = await readJson(ctx.req, voteSchema);
  const p = await ctx.env.DB.prepare(`SELECT * FROM polls WHERE id = ? AND status = 'published'`).bind(ctx.params.id).first<PollRow>();
  if (!p) throw notFound();
  if (pollState(p, ctx.now) !== 'active') throw new HttpError(409, 'poll_not_active');
  const elig = await eligibility(ctx.env.DB, p, me.id);
  if (!elig.eligible) throw forbidden(elig.reason ?? 'not_eligible');
  const opt = await ctx.env.DB.prepare('SELECT 1 AS x FROM poll_options WHERE id = ? AND poll_id = ?').bind(optionId, p.id).first();
  if (!opt) throw badRequest('invalid_option');

  const receipt = randomToken(12);
  const db = ctx.env.DB;
  try {
    // One transaction: the participation PRIMARY KEY (poll_id, user_id) rejects a second vote,
    // which rolls back the ballot insert too. The time window is re-checked inside the transaction.
    await db.batch([
      db.prepare(
        `INSERT INTO poll_participation (poll_id, user_id, voted_at, receipt)
         SELECT ?1, ?2, ?3, ?4 WHERE EXISTS (SELECT 1 FROM polls WHERE id = ?1 AND status = 'published' AND starts_at <= ?3 AND ends_at > ?3)`,
      ).bind(p.id, me.id, ctx.now, receipt),
      db.prepare(
        `INSERT INTO votes (id, poll_id, option_id, user_id)
         SELECT ?1, ?2, ?3, ?4 WHERE EXISTS (SELECT 1 FROM poll_participation WHERE poll_id = ?2 AND user_id = ?5 AND receipt = ?6)`,
      ).bind(uuid(), p.id, optionId, p.anonymous ? null : me.id, me.id, receipt),
    ]);
  } catch (e) {
    if (String(e).includes('UNIQUE') || String(e).includes('PRIMARY KEY')) throw conflict('already_voted');
    throw e;
  }
  const check = await db.prepare('SELECT receipt FROM poll_participation WHERE poll_id = ? AND user_id = ?').bind(p.id, me.id).first<{ receipt: string }>();
  if (check?.receipt !== receipt) throw new HttpError(409, 'poll_not_active');
  return json({ ok: true, receipt, votedAt: ctx.now }, 201);
}

// ───────────────────────── Admin ─────────────────────────

const pollBase = {
  titleAr: z.string().trim().min(3).max(200),
  titleFr: z.string().trim().max(200).nullish(),
  descriptionAr: z.string().trim().max(5000).nullish(),
  descriptionFr: z.string().trim().max(5000).nullish(),
  startsAt: z.number().int().positive(),
  endsAt: z.number().int().positive(),
  eligibility: z.enum(['verified', 'members']).default('verified'),
  resultVisibility: z.enum(['live', 'after_close', 'admins']).default('after_close'),
  anonymous: z.boolean().default(true),
  status: z.enum(['draft', 'published', 'cancelled']).default('draft'),
};
const optionSchema = z.object({
  labelAr: z.string().trim().min(1).max(200),
  labelFr: z.string().trim().max(200).nullish(),
  description: z.string().trim().max(1000).nullish(),
  imageUrl: z.string().url().startsWith('https://').max(500).nullish(),
});
const createPollSchema = z.object({ ...pollBase, options: z.array(optionSchema).min(2).max(50) })
  .refine((p) => p.endsAt > p.startsAt, { message: 'ends_before_start', path: ['endsAt'] });

export async function adminCreatePoll(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'ADMIN');
  const b = await readJson(ctx.req, createPollSchema);
  const id = uuid();
  const db = ctx.env.DB;
  const stmts = [
    db.prepare(
      `INSERT INTO polls (id, title_ar, title_fr, description_ar, description_fr, starts_at, ends_at, eligibility, result_visibility, anonymous, status, created_by, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(id, b.titleAr, b.titleFr ?? null, b.descriptionAr ?? null, b.descriptionFr ?? null, b.startsAt, b.endsAt, b.eligibility, b.resultVisibility, b.anonymous ? 1 : 0, b.status, ctx.user!.id, ctx.now, ctx.now),
    ...b.options.map((o, i) =>
      db.prepare('INSERT INTO poll_options (id, poll_id, label_ar, label_fr, description, image_url, position) VALUES (?, ?, ?, ?, ?, ?, ?)')
        .bind(uuid(), id, o.labelAr, o.labelFr ?? null, o.description ?? null, o.imageUrl ?? null, i)),
    adminAudit(ctx, 'create', 'poll', id, { title: b.titleAr, eligibility: b.eligibility, anonymous: b.anonymous }),
  ];
  await db.batch(stmts);
  if (b.status === 'published') {
    ctx.waitUntil(queueNotification(ctx, { topic: 'polls', titleAr: 'تصويت جديد', titleFr: 'Nouveau vote', bodyAr: b.titleAr, bodyFr: b.titleFr ?? b.titleAr, deepLink: `app://polls/${id}` }));
  }
  return json({ id }, 201);
}

const updatePollSchema = z.object({ ...pollBase, options: z.array(optionSchema).min(2).max(50).optional() }).partial().strict();

export async function adminUpdatePoll(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'ADMIN');
  const b = await readJson(ctx.req, updatePollSchema);
  const db = ctx.env.DB;
  const p = await db.prepare('SELECT * FROM polls WHERE id = ?').bind(ctx.params.id).first<PollRow>();
  if (!p) throw notFound();
  const hasVotes = !!(await db.prepare('SELECT 1 AS x FROM poll_participation WHERE poll_id = ? LIMIT 1').bind(p.id).first());
  // Once voting has begun, the rules of the ballot are frozen; only closing early / text fixes / cancelling are allowed.
  if (hasVotes && (b.options || b.eligibility || b.anonymous !== undefined || b.startsAt || (b.resultVisibility && b.resultVisibility !== p.result_visibility))) {
    throw conflict('poll_locked_after_votes');
  }
  if (hasVotes && b.endsAt && b.endsAt > p.ends_at) throw conflict('cannot_extend_after_votes');
  const startsAt = b.startsAt ?? p.starts_at;
  const endsAt = b.endsAt ?? p.ends_at;
  if (endsAt <= startsAt) throw badRequest('ends_before_start');
  const stmts: D1PreparedStatement[] = [
    db.prepare(
      `UPDATE polls SET title_ar = ?, title_fr = ?, description_ar = ?, description_fr = ?, starts_at = ?, ends_at = ?, eligibility = ?,
       result_visibility = ?, anonymous = ?, status = ?, updated_at = ? WHERE id = ?`,
    ).bind(
      b.titleAr ?? p.title_ar, b.titleFr === undefined ? p.title_fr : b.titleFr, b.descriptionAr === undefined ? p.description_ar : b.descriptionAr,
      b.descriptionFr === undefined ? p.description_fr : b.descriptionFr, startsAt, endsAt, b.eligibility ?? p.eligibility,
      b.resultVisibility ?? p.result_visibility, b.anonymous === undefined ? p.anonymous : b.anonymous ? 1 : 0, b.status ?? p.status, ctx.now, p.id,
    ),
  ];
  if (b.options) {
    stmts.push(db.prepare('DELETE FROM poll_options WHERE poll_id = ?').bind(p.id));
    b.options.forEach((o, i) => stmts.push(
      db.prepare('INSERT INTO poll_options (id, poll_id, label_ar, label_fr, description, image_url, position) VALUES (?, ?, ?, ?, ?, ?, ?)')
        .bind(uuid(), p.id, o.labelAr, o.labelFr ?? null, o.description ?? null, o.imageUrl ?? null, i)));
  }
  stmts.push(adminAudit(ctx, 'update', 'poll', p.id, Object.keys(b)));
  await db.batch(stmts);
  return json({ ok: true });
}

export async function adminListPolls(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'ADMIN');
  const rows = await ctx.env.DB.prepare(
    `SELECT p.*, (SELECT COUNT(*) FROM poll_participation pp WHERE pp.poll_id = p.id) AS votes,
            (SELECT COUNT(*) FROM poll_eligible_members m WHERE m.poll_id = p.id) AS members
     FROM polls p ORDER BY p.created_at DESC LIMIT 200`,
  ).all<PollRow & { votes: number; members: number }>();
  return json({ items: rows.results.map((p) => ({ ...pollDto(p, ctx.now), votes: p.votes, members: p.members })) });
}

export async function adminPollResults(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'ADMIN');
  const p = await ctx.env.DB.prepare('SELECT * FROM polls WHERE id = ?').bind(ctx.params.id).first<PollRow>();
  if (!p) throw notFound();
  const [opts, r, eligibleCount] = await Promise.all([
    ctx.env.DB.prepare('SELECT id, label_ar, label_fr FROM poll_options WHERE poll_id = ? ORDER BY position').bind(p.id).all<{ id: string; label_ar: string; label_fr: string | null }>(),
    results(ctx.env.DB, p.id),
    p.eligibility === 'members'
      ? ctx.env.DB.prepare('SELECT COUNT(*) AS n FROM poll_eligible_members WHERE poll_id = ?').bind(p.id).first<{ n: number }>()
      : ctx.env.DB.prepare(`SELECT COUNT(*) AS n FROM users WHERE status = 'active' AND phone_verified_at IS NOT NULL`).first<{ n: number }>(),
  ]);
  return json({
    poll: pollDto(p, ctx.now),
    options: opts.results.map((o) => ({ id: o.id, labelAr: o.label_ar, labelFr: o.label_fr, votes: r.byOption[o.id] ?? 0 })),
    totalVotes: r.totalVotes,
    eligibleVoters: eligibleCount?.n ?? 0,
  });
}

const membersSchema = z.object({ identifiers: z.array(z.string().trim().min(3).max(254)).min(1).max(500) });

/** Adds approved members (by e-mail or phone) to a poll's eligibility list. */
export async function adminAddPollMembers(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'ADMIN');
  const { identifiers } = await readJson(ctx.req, membersSchema);
  const p = await ctx.env.DB.prepare('SELECT id FROM polls WHERE id = ?').bind(ctx.params.id).first();
  if (!p) throw notFound();
  const added: string[] = [];
  const notFoundIds: string[] = [];
  const stmts: D1PreparedStatement[] = [];
  for (const ident of identifiers) {
    const phone = normalizeAlgerianPhone(ident);
    const u = await ctx.env.DB.prepare(`SELECT id FROM users WHERE status = 'active' AND ${phone ? 'phone_e164' : 'email'} = ?`)
      .bind(phone ?? ident.toLowerCase()).first<{ id: string }>();
    if (!u) { notFoundIds.push(ident); continue; }
    added.push(u.id);
    stmts.push(ctx.env.DB.prepare('INSERT INTO poll_eligible_members (poll_id, user_id, added_by, added_at) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING')
      .bind(ctx.params.id, u.id, ctx.user!.id, ctx.now));
  }
  stmts.push(adminAudit(ctx, 'add_members', 'poll', ctx.params.id!, { count: added.length }));
  await ctx.env.DB.batch(stmts);
  return json({ added: added.length, notFound: notFoundIds });
}

export async function adminRemovePollMember(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'ADMIN');
  await ctx.env.DB.batch([
    ctx.env.DB.prepare('DELETE FROM poll_eligible_members WHERE poll_id = ? AND user_id = ?').bind(ctx.params.id, ctx.params.userId),
    adminAudit(ctx, 'remove_member', 'poll', ctx.params.id!, { userId: ctx.params.userId }),
  ]);
  return json({ ok: true });
}
