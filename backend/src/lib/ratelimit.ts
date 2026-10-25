import { tooMany } from './http';

/**
 * Fixed-window rate limiter stored in D1. Used only on sensitive endpoints
 * (auth, verification, voting, submissions) to keep D1 writes low.
 */
export async function rateLimit(db: D1Database, key: string, limit: number, windowSec: number, now: number): Promise<void> {
  const windowStart = Math.floor(now / 1000 / windowSec) * windowSec;
  const row = await db
    .prepare(
      `INSERT INTO rate_limits (key, window_start, count) VALUES (?, ?, 1)
       ON CONFLICT(key, window_start) DO UPDATE SET count = count + 1
       RETURNING count`,
    )
    .bind(key, windowStart)
    .first<{ count: number }>();
  if ((row?.count ?? 0) > limit) {
    throw tooMany(windowStart + windowSec - Math.floor(now / 1000));
  }
}

export async function purgeRateLimits(db: D1Database, now: number): Promise<void> {
  await db.prepare('DELETE FROM rate_limits WHERE window_start < ?').bind(Math.floor(now / 1000) - 86_400).run();
}
