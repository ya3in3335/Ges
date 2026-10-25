import type { Env } from '../env';
import { tgCall } from './telegramApi';

const MAX_ATTEMPTS = 8;

/** Queues a message for the private admin channel. The dedupe key prevents duplicate notifications. */
export function enqueueChannelMessage(db: D1Database, dedupeKey: string, text: string, now: number): D1PreparedStatement {
  return db
    .prepare(
      `INSERT INTO channel_outbox (dedupe_key, text, status, attempts, next_attempt_at, created_at)
       VALUES (?, ?, 'pending', 0, ?, ?) ON CONFLICT(dedupe_key) DO NOTHING`,
    )
    .bind(dedupeKey, text, now, now);
}

/**
 * Delivers due outbox messages with exponential backoff. Messages are sent one by one with
 * a small cap per run to respect Telegram's limits (~20 msgs/min per channel).
 */
export async function flushChannelOutbox(env: Env, f: typeof fetch, now: number, max = 15): Promise<{ sent: number; failed: number }> {
  const out = { sent: 0, failed: 0 };
  if (!env.TELEGRAM_BOT_TOKEN || !env.TELEGRAM_ADMIN_CHANNEL_ID) return out;
  const due = await env.DB.prepare(
    `SELECT id, text, attempts FROM channel_outbox WHERE status = 'pending' AND next_attempt_at <= ? ORDER BY id LIMIT ?`,
  ).bind(now, max).all<{ id: number; text: string; attempts: number }>();
  for (const m of due.results) {
    // Claim the row first so concurrent runs (request waitUntil + cron) don't double-send.
    const claim = await env.DB.prepare(
      `UPDATE channel_outbox SET attempts = attempts + 1, next_attempt_at = ? WHERE id = ? AND status = 'pending' AND attempts = ?`,
    ).bind(now + 120_000, m.id, m.attempts).run();
    if (!claim.meta.changes) continue;
    const r = await tgCall(env, f, 'sendMessage', { chat_id: env.TELEGRAM_ADMIN_CHANNEL_ID, text: m.text, disable_notification: false });
    if (r.ok) {
      await env.DB.prepare(`UPDATE channel_outbox SET status = 'sent', sent_at = ?, last_error = NULL WHERE id = ?`).bind(now, m.id).run();
      out.sent++;
    } else {
      const attempts = m.attempts + 1;
      const backoff = Math.max((r.retryAfter ?? 0) * 1000, Math.min(2 ** attempts * 60_000, 6 * 3600_000));
      await env.DB.prepare(
        `UPDATE channel_outbox SET status = ?, next_attempt_at = ?, last_error = ? WHERE id = ?`,
      ).bind(attempts >= MAX_ATTEMPTS ? 'failed' : 'pending', now + backoff, (r.description ?? 'unknown').slice(0, 300), m.id).run();
      out.failed++;
      if (r.retryAfter) break;
    }
  }
  return out;
}
