import { z } from 'zod';
import type { Ctx } from '../env';
import { json, pagination, readJson } from '../lib/http';
import { uuid } from '../lib/crypto';
import { requireRole } from '../lib/rbac';
import { adminAudit } from '../lib/audit';
import { fcmConfigured, sendPush } from '../services/fcm';

type Topic = 'news' | 'activities' | 'polls' | 'volunteer' | 'important';

export interface NotificationInput {
  topic: Topic;
  titleAr: string; titleFr?: string | null;
  bodyAr: string; bodyFr?: string | null;
  deepLink?: string | null;
  targetUserId?: string | null;
}

/**
 * Records a notification and delivers it via FCM when configured. Without credentials the record is
 * stored with delivery = 'not_configured' (shown in-app); success is never simulated.
 * Broadcasts go to per-locale topics (e.g. "news_ar"), to which the app subscribes according to user preferences.
 */
export async function queueNotification(ctx: Ctx, n: NotificationInput): Promise<string> {
  const id = uuid();
  const db = ctx.env.DB;
  await db.prepare(
    `INSERT INTO notifications (id, topic, title_ar, title_fr, body_ar, body_fr, target_user_id, deep_link, delivery, created_by, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).bind(id, n.topic, n.titleAr, n.titleFr ?? null, n.bodyAr, n.bodyFr ?? null, n.targetUserId ?? null, n.deepLink ?? null,
    fcmConfigured(ctx.env) ? 'queued' : 'not_configured', ctx.user?.id ?? null, ctx.now).run();
  if (!fcmConfigured(ctx.env)) return id;

  const data = { notificationId: id, topic: n.topic, deepLink: n.deepLink ?? '' };
  const errors: string[] = [];
  try {
    if (n.targetUserId) {
      const pref = await db.prepare('SELECT pref_notify_volunteer FROM users WHERE id = ?').bind(n.targetUserId).first<{ pref_notify_volunteer: number }>();
      if (n.topic !== 'volunteer' || pref?.pref_notify_volunteer) {
        const devices = await db.prepare('SELECT token, locale FROM push_devices WHERE user_id = ? LIMIT 10').bind(n.targetUserId).all<{ token: string; locale: string }>();
        for (const d of devices.results) {
          const fr = d.locale === 'fr';
          const r = await sendPush(ctx.env, ctx.fetch, { token: d.token }, { title: fr && n.titleFr ? n.titleFr : n.titleAr, body: fr && n.bodyFr ? n.bodyFr : n.bodyAr, data }, ctx.now);
          if (!r.ok) {
            errors.push(r.error ?? 'error');
            if (r.unregistered) await db.prepare('DELETE FROM push_devices WHERE token = ?').bind(d.token).run();
          }
        }
      }
    } else {
      for (const lang of ['ar', 'fr'] as const) {
        const fr = lang === 'fr';
        const r = await sendPush(ctx.env, ctx.fetch, { topic: `${n.topic}_${lang}` }, { title: fr && n.titleFr ? n.titleFr : n.titleAr, body: fr && n.bodyFr ? n.bodyFr : n.bodyAr, data }, ctx.now);
        if (!r.ok) errors.push(r.error ?? 'error');
      }
    }
  } catch (e) {
    errors.push(e instanceof Error ? e.message : 'error');
  }
  await db.prepare('UPDATE notifications SET delivery = ?, delivery_error = ?, sent_at = ? WHERE id = ?')
    .bind(errors.length ? 'failed' : 'sent', errors.length ? errors.join(',').slice(0, 300) : null, errors.length ? null : ctx.now, id).run();
  return id;
}

const sendSchema = z.object({
  topic: z.enum(['news', 'activities', 'polls', 'volunteer', 'important']),
  titleAr: z.string().trim().min(2).max(120),
  titleFr: z.string().trim().max(120).nullish(),
  bodyAr: z.string().trim().min(2).max(500),
  bodyFr: z.string().trim().max(500).nullish(),
  deepLink: z.string().max(200).regex(/^app:\/\/[a-z0-9/_-]+$/i).nullish(),
});

export async function adminSendNotification(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'ADMIN');
  const b = await readJson(ctx.req, sendSchema);
  const id = await queueNotification(ctx, b);
  await adminAudit(ctx, 'send', 'notification', id, { topic: b.topic }).run();
  const row = await ctx.env.DB.prepare('SELECT delivery, delivery_error FROM notifications WHERE id = ?').bind(id).first();
  return json({ id, ...row }, 201);
}

export async function adminListNotifications(ctx: Ctx): Promise<Response> {
  requireRole(ctx, 'EDITOR');
  const { limit, offset } = pagination(ctx.url);
  const rows = await ctx.env.DB.prepare(
    `SELECT id, topic, title_ar, body_ar, target_user_id, delivery, delivery_error, created_at, sent_at FROM notifications
     ORDER BY created_at DESC LIMIT ? OFFSET ?`,
  ).bind(limit, offset).all();
  return json({ items: rows.results, fcmConfigured: fcmConfigured(ctx.env) });
}
