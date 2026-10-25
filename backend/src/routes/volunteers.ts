import { z } from 'zod';
import type { Ctx } from '../env';
import { conflict, json, notFound, pagination, readJson } from '../lib/http';
import { uuid } from '../lib/crypto';
import { rateLimit } from '../lib/ratelimit';
import { requireRole, requireUser } from '../lib/rbac';
import { adminAudit } from '../lib/audit';
import { queueNotification } from './notifications';

const applySchema = z.object({
  fullName: z.string().trim().min(3).max(100),
  contact: z.string().trim().min(5).max(200),
  skills: z.string().trim().min(2).max(1000),
  availability: z.string().trim().min(2).max(500),
  preferredAreas: z.string().trim().max(500).nullish(),
  experience: z.string().trim().max(2000).nullish(),
});

const mine = (r: Record<string, unknown>) => ({
  id: r.id, fullName: r.full_name, contact: r.contact, skills: r.skills, availability: r.availability,
  preferredAreas: r.preferred_areas, experience: r.experience, status: r.status, reviewNote: r.review_note,
  createdAt: r.created_at, updatedAt: r.updated_at,
});

export async function applyVolunteer(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  await rateLimit(ctx.env.DB, `volunteer:${me.id}`, 5, 3600, ctx.now);
  const b = await readJson(ctx.req, applySchema);
  const existing = await ctx.env.DB.prepare('SELECT id, status FROM volunteers WHERE user_id = ?').bind(me.id).first<{ id: string; status: string }>();
  if (existing && existing.status !== 'rejected') throw conflict('application_exists');
  const id = existing?.id ?? uuid();
  // A rejected applicant may re-apply; the record returns to "pending".
  await ctx.env.DB.prepare(
    `INSERT INTO volunteers (id, user_id, full_name, contact, skills, availability, preferred_areas, experience, status, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?)
     ON CONFLICT(user_id) DO UPDATE SET full_name = excluded.full_name, contact = excluded.contact, skills = excluded.skills,
       availability = excluded.availability, preferred_areas = excluded.preferred_areas, experience = excluded.experience,
       status = 'pending', review_note = NULL, reviewed_by = NULL, reviewed_at = NULL, updated_at = excluded.updated_at`,
  ).bind(id, me.id, b.fullName, b.contact, b.skills, b.availability, b.preferredAreas ?? null, b.experience ?? null, ctx.now, ctx.now).run();
  return json({ id, status: 'pending' }, 201);
}

export async function myVolunteer(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  const r = await ctx.env.DB.prepare('SELECT * FROM volunteers WHERE user_id = ?').bind(me.id).first<Record<string, unknown>>();
  if (!r) return json({ application: null, assignments: [] });
  const assignments = await ctx.env.DB.prepare(
    `SELECT a.id, a.title_ar, a.title_fr, a.starts_at, a.ends_at, a.location, va.role_note FROM volunteer_assignments va
     JOIN activities a ON a.id = va.activity_id WHERE va.volunteer_id = ? ORDER BY a.starts_at DESC`,
  ).bind(r.id).all();
  return json({ application: mine(r), assignments: assignments.results });
}

export async function withdrawVolunteer(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  await ctx.env.DB.prepare('DELETE FROM volunteers WHERE user_id = ?').bind(me.id).run();
  return json({ ok: true });
}

export async function adminListVolunteers(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'ADMIN');
  const { limit, offset, page } = pagination(ctx.url);
  const status = ctx.url.searchParams.get('status');
  const q = ctx.url.searchParams.get('q')?.trim().slice(0, 100);
  const where: string[] = [];
  const binds: unknown[] = [];
  if (status && ['pending', 'approved', 'rejected'].includes(status)) { where.push('v.status = ?'); binds.push(status); }
  if (q) { where.push(`(v.full_name LIKE ? ESCAPE '\\' OR v.skills LIKE ? ESCAPE '\\')`); const l = `%${q.replace(/[\\%_]/g, (c) => `\\${c}`)}%`; binds.push(l, l); }
  const rows = await ctx.env.DB.prepare(
    `SELECT v.*, u.email, u.phone_e164, u.phone_verified_at FROM volunteers v JOIN users u ON u.id = v.user_id
     ${where.length ? 'WHERE ' + where.join(' AND ') : ''} ORDER BY v.created_at DESC LIMIT ? OFFSET ?`,
  ).bind(...binds, limit + 1, offset).all<Record<string, unknown>>();
  return json({ items: rows.results.slice(0, limit), page, hasMore: rows.results.length > limit });
}

export async function adminReviewVolunteer(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'ADMIN');
  const b = await readJson(ctx.req, z.object({ status: z.enum(['approved', 'rejected', 'pending']), note: z.string().trim().max(500).nullish() }));
  const v = await ctx.env.DB.prepare('SELECT id, user_id FROM volunteers WHERE id = ?').bind(ctx.params.id).first<{ id: string; user_id: string }>();
  if (!v) throw notFound();
  const stmts = [
    ctx.env.DB.prepare('UPDATE volunteers SET status = ?, review_note = ?, reviewed_by = ?, reviewed_at = ?, updated_at = ? WHERE id = ?')
      .bind(b.status, b.note ?? null, ctx.user!.id, ctx.now, ctx.now, v.id),
    adminAudit(ctx, `volunteer_${b.status}`, 'volunteer', v.id),
  ];
  // Approved volunteers get the VOLUNTEER role (never downgrades staff roles).
  if (b.status === 'approved') stmts.push(ctx.env.DB.prepare(`UPDATE users SET role = 'VOLUNTEER', updated_at = ? WHERE id = ? AND role = 'USER'`).bind(ctx.now, v.user_id));
  else stmts.push(ctx.env.DB.prepare(`UPDATE users SET role = 'USER', updated_at = ? WHERE id = ? AND role = 'VOLUNTEER'`).bind(ctx.now, v.user_id));
  await ctx.env.DB.batch(stmts);
  if (b.status !== 'pending') {
    ctx.waitUntil(queueNotification(ctx, {
      topic: 'volunteer', targetUserId: v.user_id,
      titleAr: 'تحديث طلب التطوع', titleFr: 'Mise à jour de votre candidature',
      bodyAr: b.status === 'approved' ? 'تمت الموافقة على طلب التطوع الخاص بك. شكراً لك!' : 'نعتذر، لم يتم قبول طلب التطوع في الوقت الحالي.',
      bodyFr: b.status === 'approved' ? 'Votre candidature de bénévole a été acceptée. Merci !' : 'Votre candidature n’a pas été retenue pour le moment.',
      deepLink: 'app://volunteer',
    }));
  }
  return json({ ok: true });
}

export async function adminAssignVolunteer(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'ADMIN');
  const b = await readJson(ctx.req, z.object({ activityId: z.string().uuid(), roleNote: z.string().trim().max(300).nullish() }));
  const v = await ctx.env.DB.prepare(`SELECT id FROM volunteers WHERE id = ? AND status = 'approved'`).bind(ctx.params.id).first();
  if (!v) throw conflict('volunteer_not_approved');
  await ctx.env.DB.batch([
    ctx.env.DB.prepare(
      `INSERT INTO volunteer_assignments (volunteer_id, activity_id, role_note, assigned_by, assigned_at) VALUES (?, ?, ?, ?, ?)
       ON CONFLICT DO UPDATE SET role_note = excluded.role_note`,
    ).bind(ctx.params.id, b.activityId, b.roleNote ?? null, ctx.user!.id, ctx.now),
    adminAudit(ctx, 'assign', 'volunteer', ctx.params.id!, { activityId: b.activityId }),
  ]);
  return json({ ok: true });
}

export async function adminUnassignVolunteer(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'ADMIN');
  await ctx.env.DB.batch([
    ctx.env.DB.prepare('DELETE FROM volunteer_assignments WHERE volunteer_id = ? AND activity_id = ?').bind(ctx.params.id, ctx.params.activityId),
    adminAudit(ctx, 'unassign', 'volunteer', ctx.params.id!, { activityId: ctx.params.activityId }),
  ]);
  return json({ ok: true });
}
