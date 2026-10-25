import { z } from 'zod';
import type { Ctx } from '../env';
import { json, notFound, pagination, readJson } from '../lib/http';
import { uuid } from '../lib/crypto';
import { requireRole } from '../lib/rbac';
import { adminAudit } from '../lib/audit';
import { queueNotification } from './notifications';

const CATEGORIES = ['news', 'announcement', 'campaign', 'report'] as const;

interface NewsRow {
  id: string; title_ar: string; title_fr: string | null; body_ar: string; body_fr: string | null; category: string;
  image_url: string | null; featured: number; status: string; published_at: number | null; created_at: number; updated_at: number;
}

const dto = (n: NewsRow, full = true) => ({
  id: n.id, titleAr: n.title_ar, titleFr: n.title_fr, category: n.category, imageUrl: n.image_url,
  featured: !!n.featured, status: n.status, publishedAt: n.published_at, updatedAt: n.updated_at,
  ...(full ? { bodyAr: n.body_ar, bodyFr: n.body_fr } : { excerptAr: n.body_ar.slice(0, 180), excerptFr: n.body_fr?.slice(0, 180) ?? null }),
});

// LIKE wildcards in user input are escaped so search terms are matched literally.
const likeEscape = (s: string) => `%${s.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;

export async function listNews(ctx: Ctx): Promise<Response> {
  const { limit, offset, page } = pagination(ctx.url);
  const category = ctx.url.searchParams.get('category');
  const q = ctx.url.searchParams.get('q')?.trim().slice(0, 100);
  const featured = ctx.url.searchParams.get('featured') === '1';
  const where = [`status = 'published'`, 'published_at <= ?'];
  const binds: unknown[] = [ctx.now];
  if (category && (CATEGORIES as readonly string[]).includes(category)) { where.push('category = ?'); binds.push(category); }
  if (featured) where.push('featured = 1');
  if (q) { where.push(`(title_ar LIKE ? ESCAPE '\\' OR title_fr LIKE ? ESCAPE '\\' OR body_ar LIKE ? ESCAPE '\\' OR body_fr LIKE ? ESCAPE '\\')`); const l = likeEscape(q); binds.push(l, l, l, l); }
  const rows = await ctx.env.DB.prepare(
    `SELECT * FROM news WHERE ${where.join(' AND ')} ORDER BY published_at DESC LIMIT ? OFFSET ?`,
  ).bind(...binds, limit + 1, offset).all<NewsRow>();
  const items = rows.results.slice(0, limit).map((n) => dto(n, false));
  return json({ items, page, hasMore: rows.results.length > limit }, 200, { 'cache-control': 'public, max-age=60' });
}

export async function getNews(ctx: Ctx): Promise<Response> {
  const n = await ctx.env.DB.prepare(`SELECT * FROM news WHERE id = ? AND status = 'published' AND published_at <= ?`).bind(ctx.params.id, ctx.now).first<NewsRow>();
  if (!n) throw notFound();
  return json({ item: dto(n) }, 200, { 'cache-control': 'public, max-age=60' });
}

const newsSchema = z.object({
  titleAr: z.string().trim().min(3).max(200),
  titleFr: z.string().trim().max(200).nullish(),
  bodyAr: z.string().trim().min(1).max(20_000),
  bodyFr: z.string().trim().max(20_000).nullish(),
  category: z.enum(CATEGORIES).default('news'),
  imageUrl: z.string().max(500).refine((u) => u.startsWith('https://') || u.startsWith('/media/')).nullish(),
  featured: z.boolean().default(false),
  status: z.enum(['draft', 'published', 'archived']).default('draft'),
  publishedAt: z.number().int().positive().nullish(),
  notify: z.boolean().default(false),
});

export async function adminListNews(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'EDITOR');
  const { limit, offset, page } = pagination(ctx.url);
  const status = ctx.url.searchParams.get('status');
  const rows = await ctx.env.DB.prepare(
    `SELECT * FROM news ${status ? 'WHERE status = ?' : ''} ORDER BY updated_at DESC LIMIT ? OFFSET ?`,
  ).bind(...(status ? [status] : []), limit + 1, offset).all<NewsRow>();
  return json({ items: rows.results.slice(0, limit).map((n) => dto(n)), page, hasMore: rows.results.length > limit });
}

export async function adminCreateNews(ctx: Ctx): Promise<Response> {
  const u = requireRole(ctx, 'EDITOR');
  const b = await readJson(ctx.req, newsSchema);
  const id = uuid();
  const publishedAt = b.status === 'published' ? (b.publishedAt ?? ctx.now) : (b.publishedAt ?? null);
  await ctx.env.DB.batch([
    ctx.env.DB.prepare(
      `INSERT INTO news (id, title_ar, title_fr, body_ar, body_fr, category, image_url, featured, status, published_at, author_id, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(id, b.titleAr, b.titleFr ?? null, b.bodyAr, b.bodyFr ?? null, b.category, b.imageUrl ?? null, b.featured ? 1 : 0, b.status, publishedAt, u.id, ctx.now, ctx.now),
    adminAudit(ctx, 'create', 'news', id, { status: b.status }),
  ]);
  if (b.notify && b.status === 'published') {
    ctx.waitUntil(queueNotification(ctx, { topic: b.category === 'announcement' ? 'important' : 'news', titleAr: b.titleAr, titleFr: b.titleFr, bodyAr: b.bodyAr.slice(0, 140), bodyFr: b.bodyFr?.slice(0, 140), deepLink: `app://news/${id}` }));
  }
  return json({ id }, 201);
}

export async function adminUpdateNews(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'EDITOR');
  const b = await readJson(ctx.req, newsSchema.partial());
  const n = await ctx.env.DB.prepare('SELECT * FROM news WHERE id = ?').bind(ctx.params.id).first<NewsRow>();
  if (!n) throw notFound();
  const status = b.status ?? n.status;
  const publishedAt = b.publishedAt ?? (status === 'published' && !n.published_at ? ctx.now : n.published_at);
  await ctx.env.DB.batch([
    ctx.env.DB.prepare(
      `UPDATE news SET title_ar = ?, title_fr = ?, body_ar = ?, body_fr = ?, category = ?, image_url = ?, featured = ?, status = ?, published_at = ?, updated_at = ? WHERE id = ?`,
    ).bind(
      b.titleAr ?? n.title_ar, b.titleFr === undefined ? n.title_fr : b.titleFr, b.bodyAr ?? n.body_ar, b.bodyFr === undefined ? n.body_fr : b.bodyFr,
      b.category ?? n.category, b.imageUrl === undefined ? n.image_url : b.imageUrl, b.featured === undefined ? n.featured : b.featured ? 1 : 0,
      status, publishedAt, ctx.now, n.id,
    ),
    adminAudit(ctx, 'update', 'news', n.id, { status }),
  ]);
  if (b.notify && status === 'published') {
    ctx.waitUntil(queueNotification(ctx, { topic: 'news', titleAr: b.titleAr ?? n.title_ar, titleFr: b.titleFr ?? n.title_fr, bodyAr: (b.bodyAr ?? n.body_ar).slice(0, 140), deepLink: `app://news/${n.id}` }));
  }
  return json({ ok: true });
}

export async function adminDeleteNews(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'ADMIN');
  await ctx.env.DB.batch([
    ctx.env.DB.prepare('DELETE FROM news WHERE id = ?').bind(ctx.params.id),
    adminAudit(ctx, 'delete', 'news', ctx.params.id!),
  ]);
  return json({ ok: true });
}
