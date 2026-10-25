import type { Ctx } from '../env';
import { json } from '../lib/http';
import { publicSettings } from './settings';

/** One aggregated request for the home screen (keeps request count and D1 reads low). */
export async function home(ctx: Ctx): Promise<Response> {
  const db = ctx.env.DB;
  const now = ctx.now;
  const [featured, announcements, news, activities, polls, media, donation] = await db.batch([
    db.prepare(`SELECT id, title_ar, title_fr, image_url, category, published_at, substr(body_ar, 1, 160) AS excerpt_ar, substr(body_fr, 1, 160) AS excerpt_fr
                FROM news WHERE status = 'published' AND published_at <= ? AND featured = 1 ORDER BY published_at DESC LIMIT 5`).bind(now),
    db.prepare(`SELECT id, title_ar, title_fr, image_url, category, published_at, substr(body_ar, 1, 160) AS excerpt_ar, substr(body_fr, 1, 160) AS excerpt_fr
                FROM news WHERE status = 'published' AND published_at <= ? AND category = 'announcement' ORDER BY published_at DESC LIMIT 3`).bind(now),
    db.prepare(`SELECT id, title_ar, title_fr, image_url, category, published_at, substr(body_ar, 1, 160) AS excerpt_ar, substr(body_fr, 1, 160) AS excerpt_fr
                FROM news WHERE status = 'published' AND published_at <= ? AND category <> 'announcement' ORDER BY published_at DESC LIMIT 6`).bind(now),
    db.prepare(`SELECT id, title_ar, title_fr, location, starts_at, ends_at, image_url, is_campaign, featured FROM activities
                WHERE status = 'published' AND ends_at > ? ORDER BY featured DESC, starts_at ASC LIMIT 8`).bind(now),
    db.prepare(`SELECT id, title_ar, title_fr, ends_at FROM polls WHERE status = 'published' AND starts_at <= ?1 AND ends_at > ?1 ORDER BY ends_at LIMIT 5`).bind(now),
    db.prepare(`SELECT id, kind, url, thumbnail_url, title_ar, title_fr FROM media WHERE status = 'published' ORDER BY featured DESC, created_at DESC LIMIT 10`),
    db.prepare(`SELECT ccp_account, bank_account, instructions_ar, instructions_fr FROM donation_settings WHERE id = 1`),
  ]);
  const d = donation!.results[0] as Record<string, unknown> | undefined;
  return json({
    settings: await publicSettings(db),
    featuredNews: featured!.results,
    announcements: announcements!.results,
    recentNews: news!.results,
    activities: activities!.results,
    activePolls: polls!.results,
    media: media!.results,
    donationAvailable: !!(d?.ccp_account || d?.bank_account || d?.instructions_ar),
    serverTime: now,
  }, 200, { 'cache-control': 'public, max-age=60' });
}
