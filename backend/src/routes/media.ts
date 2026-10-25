import { z } from 'zod';
import type { Ctx } from '../env';
import { badRequest, HttpError, json, notFound, pagination, readJson } from '../lib/http';
import { uuid } from '../lib/crypto';
import { requireRole } from '../lib/rbac';
import { adminAudit } from '../lib/audit';

const IMAGE_MAX = 8 * 1024 * 1024;
const VIDEO_MAX = 60 * 1024 * 1024;

/** Detects the real file type from magic bytes; the client-declared type is never trusted. */
export function sniffType(b: Uint8Array): { mime: string; ext: string; kind: 'photo' | 'video' } | null {
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return { mime: 'image/jpeg', ext: 'jpg', kind: 'photo' };
  if (b.length >= 8 && [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a].every((v, i) => b[i] === v)) return { mime: 'image/png', ext: 'png', kind: 'photo' };
  const ascii = (s: number, e: number) => String.fromCharCode(...b.slice(s, e));
  if (b.length >= 12 && ascii(0, 4) === 'RIFF' && ascii(8, 12) === 'WEBP') return { mime: 'image/webp', ext: 'webp', kind: 'photo' };
  if (b.length >= 12 && ascii(4, 8) === 'ftyp') return { mime: 'video/mp4', ext: 'mp4', kind: 'video' };
  return null;
}

interface MediaRow {
  id: string; kind: string; title_ar: string | null; title_fr: string | null; category: string; url: string; thumbnail_url: string | null;
  activity_id: string | null; featured: number; status: string; created_at: number; content_type: string | null; size_bytes: number | null;
}
const dto = (m: MediaRow) => ({
  id: m.id, kind: m.kind, titleAr: m.title_ar, titleFr: m.title_fr, category: m.category, url: m.url, thumbnailUrl: m.thumbnail_url,
  activityId: m.activity_id, featured: !!m.featured, createdAt: m.created_at,
});

export async function listMedia(ctx: Ctx): Promise<Response> {
  const { limit, offset, page } = pagination(ctx.url, 60);
  const kind = ctx.url.searchParams.get('kind');
  const category = ctx.url.searchParams.get('category');
  const where = [`status = 'published'`];
  const binds: unknown[] = [];
  if (kind === 'photo' || kind === 'video') { where.push('kind = ?'); binds.push(kind); }
  if (category) { where.push('category = ?'); binds.push(category.slice(0, 50)); }
  if (ctx.url.searchParams.get('featured') === '1') where.push('featured = 1');
  const rows = await ctx.env.DB.prepare(`SELECT * FROM media WHERE ${where.join(' AND ')} ORDER BY created_at DESC LIMIT ? OFFSET ?`)
    .bind(...binds, limit + 1, offset).all<MediaRow>();
  const cats = await ctx.env.DB.prepare(`SELECT DISTINCT category FROM media WHERE status = 'published' ORDER BY category LIMIT 50`).all<{ category: string }>();
  return json({ items: rows.results.slice(0, limit).map(dto), categories: cats.results.map((c) => c.category), page, hasMore: rows.results.length > limit },
    200, { 'cache-control': 'public, max-age=120' });
}

/** Serves R2 objects with long-lived caching (keys are immutable). */
export async function serveMedia(ctx: Ctx): Promise<Response> {
  if (!ctx.env.MEDIA) throw notFound();
  const key = ctx.params.key ?? '';
  if (!/^[a-f0-9-]{36}\.(jpg|png|webp|mp4)$/.test(key)) throw notFound();
  const range = ctx.req.headers.get('range');
  const obj = await ctx.env.MEDIA.get(key, range ? { range: ctx.req.headers } : undefined);
  if (!obj) throw notFound();
  const headers = new Headers();
  obj.writeHttpMetadata(headers);
  headers.set('etag', obj.httpEtag);
  headers.set('cache-control', 'public, max-age=31536000, immutable');
  headers.set('x-content-type-options', 'nosniff');
  headers.set('accept-ranges', 'bytes');
  headers.set('content-security-policy', "default-src 'none'");
  if (range && 'range' in obj && obj.range) {
    const r = obj.range as { offset: number; length: number };
    headers.set('content-range', `bytes ${r.offset}-${r.offset + r.length - 1}/${obj.size}`);
    return new Response('body' in obj ? (obj as R2ObjectBody).body : null, { status: 206, headers });
  }
  return new Response('body' in obj ? (obj as R2ObjectBody).body : null, { headers });
}

const externalSchema = z.object({
  kind: z.enum(['photo', 'video']),
  url: z.string().url().startsWith('https://').max(500),
  thumbnailUrl: z.string().url().startsWith('https://').max(500).nullish(),
  titleAr: z.string().trim().max(200).nullish(),
  titleFr: z.string().trim().max(200).nullish(),
  category: z.string().trim().min(1).max(50).default('general'),
  activityId: z.string().uuid().nullish(),
  featured: z.boolean().default(false),
});

export async function adminAddExternalMedia(ctx: Ctx): Promise<Response> {
  const u = requireRole(ctx, 'EDITOR');
  const b = await readJson(ctx.req, externalSchema);
  const id = uuid();
  await ctx.env.DB.batch([
    ctx.env.DB.prepare(
      `INSERT INTO media (id, kind, title_ar, title_fr, category, url, thumbnail_url, activity_id, featured, uploaded_by, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(id, b.kind, b.titleAr ?? null, b.titleFr ?? null, b.category, b.url, b.thumbnailUrl ?? null, b.activityId ?? null, b.featured ? 1 : 0, u.id, ctx.now),
    adminAudit(ctx, 'create_external', 'media', id, { kind: b.kind }),
  ]);
  return json({ id }, 201);
}

export async function adminUploadMedia(ctx: Ctx): Promise<Response> {
  const u = requireRole(ctx, 'EDITOR');
  if (!ctx.env.MEDIA) throw new HttpError(503, 'r2_not_configured');
  const len = parseInt(ctx.req.headers.get('content-length') ?? '0', 10);
  if (len > VIDEO_MAX + 64 * 1024) throw new HttpError(413, 'payload_too_large');
  const form = await ctx.req.formData();
  const file = form.get('file');
  if (!file || typeof file === 'string') throw badRequest('file_required');
  const head = new Uint8Array(await file.slice(0, 16).arrayBuffer());
  const t = sniffType(head);
  if (!t) throw badRequest('unsupported_file_type');
  if (file.size > (t.kind === 'photo' ? IMAGE_MAX : VIDEO_MAX)) throw new HttpError(413, 'file_too_large');
  const id = uuid();
  const key = `${id}.${t.ext}`;
  await ctx.env.MEDIA.put(key, file.stream(), { httpMetadata: { contentType: t.mime } });
  const str = (k: string, max: number) => { const v = form.get(k); return typeof v === 'string' && v.trim() ? v.trim().slice(0, max) : null; };
  const activityId = str('activityId', 36);
  const url = `${ctx.env.PUBLIC_BASE_URL || ''}/media/${key}`;
  await ctx.env.DB.batch([
    ctx.env.DB.prepare(
      `INSERT INTO media (id, kind, title_ar, title_fr, category, url, thumbnail_url, r2_key, content_type, size_bytes, activity_id, featured, uploaded_by, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).bind(id, t.kind, str('titleAr', 200), str('titleFr', 200), str('category', 50) ?? 'general', url, t.kind === 'photo' ? url : null, key, t.mime, file.size,
      activityId && /^[0-9a-f-]{36}$/.test(activityId) ? activityId : null, str('featured', 5) === 'true' ? 1 : 0, u.id, ctx.now),
    adminAudit(ctx, 'upload', 'media', id, { kind: t.kind, size: file.size }),
  ]);
  return json({ id, url }, 201);
}

export async function adminUpdateMedia(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'EDITOR');
  const b = await readJson(ctx.req, z.object({
    titleAr: z.string().trim().max(200).nullish(), titleFr: z.string().trim().max(200).nullish(),
    category: z.string().trim().min(1).max(50).optional(), featured: z.boolean().optional(), status: z.enum(['published', 'hidden']).optional(),
  }).strict());
  const m = await ctx.env.DB.prepare('SELECT * FROM media WHERE id = ?').bind(ctx.params.id).first<MediaRow>();
  if (!m) throw notFound();
  await ctx.env.DB.batch([
    ctx.env.DB.prepare('UPDATE media SET title_ar = ?, title_fr = ?, category = ?, featured = ?, status = ? WHERE id = ?').bind(
      b.titleAr === undefined ? m.title_ar : b.titleAr, b.titleFr === undefined ? m.title_fr : b.titleFr, b.category ?? m.category,
      b.featured === undefined ? m.featured : +b.featured, b.status ?? m.status, m.id),
    adminAudit(ctx, 'update', 'media', m.id, Object.keys(b)),
  ]);
  return json({ ok: true });
}

export async function adminDeleteMedia(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'EDITOR');
  const m = await ctx.env.DB.prepare('SELECT r2_key FROM media WHERE id = ?').bind(ctx.params.id).first<{ r2_key: string | null }>();
  if (!m) throw notFound();
  if (m.r2_key && ctx.env.MEDIA) await ctx.env.MEDIA.delete(m.r2_key);
  await ctx.env.DB.batch([
    ctx.env.DB.prepare('DELETE FROM media WHERE id = ?').bind(ctx.params.id),
    adminAudit(ctx, 'delete', 'media', ctx.params.id!),
  ]);
  return json({ ok: true });
}

export async function adminListMedia(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'EDITOR');
  const { limit, offset, page } = pagination(ctx.url, 60);
  const rows = await ctx.env.DB.prepare('SELECT * FROM media ORDER BY created_at DESC LIMIT ? OFFSET ?').bind(limit + 1, offset).all<MediaRow>();
  return json({ items: rows.results.slice(0, limit).map((m) => ({ ...dto(m), status: m.status, sizeBytes: m.size_bytes })), page, hasMore: rows.results.length > limit, r2Enabled: !!ctx.env.MEDIA });
}
