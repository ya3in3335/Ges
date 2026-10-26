import type { Ctx, Role } from '../env';
import { randomToken, sha256Hex, uuid } from './crypto';
import { signAccessToken } from './jwt';

export interface TokenPair {
  accessToken: string;
  accessTokenExpiresAt: number;
  refreshToken: string;
  refreshTokenExpiresAt: number;
}

export async function issueTokens(
  ctx: Ctx,
  user: { id: string; role: Role },
  client: 'android' | 'admin',
  familyId: string = uuid(),
  opts: { longLived?: boolean; deviceName?: string | null } = {},
): Promise<{ tokens: TokenPair; insert: D1PreparedStatement; sessionId: string }> {
  const sessionId = uuid();
  const refreshToken = randomToken(32);
  const accessTtl = parseInt(ctx.env.ACCESS_TOKEN_TTL_SECONDS || '900', 10);
  // Admin sessions are deliberately shorter-lived.
  // Activated supervisor devices keep a 365-day session (renewed on use, revocable at any time).
  const refreshDays = opts.longLived ? 365 : client === 'admin' ? 1 : parseInt(ctx.env.REFRESH_TOKEN_TTL_DAYS || '30', 10);
  const nowSec = Math.floor(ctx.now / 1000);
  const accessToken = await signAccessToken(ctx.env.JWT_SECRET, {
    sub: user.id,
    role: user.role,
    sid: sessionId,
    iat: nowSec,
    exp: nowSec + accessTtl,
  });
  const refreshExp = ctx.now + refreshDays * 86_400_000;
  const insert = ctx.env.DB.prepare(
    `INSERT INTO auth_sessions (id, user_id, refresh_hash, family_id, client, created_at, expires_at, user_agent, long_lived, device_name, last_used_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).bind(sessionId, user.id, await sha256Hex(refreshToken), familyId, client, ctx.now, refreshExp, (ctx.req.headers.get('user-agent') ?? '').slice(0, 200),
    opts.longLived ? 1 : 0, opts.deviceName ?? null, ctx.now);
  return {
    sessionId,
    insert,
    tokens: { accessToken, accessTokenExpiresAt: (nowSec + accessTtl) * 1000, refreshToken, refreshTokenExpiresAt: refreshExp },
  };
}
