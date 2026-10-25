import { b64url, b64urlDecode, hmacSha256, timingSafeEqual } from './crypto';
import type { Role } from '../env';

const enc = new TextEncoder();
const dec = new TextDecoder();

export interface AccessClaims {
  sub: string;
  role: Role;
  sid: string;
  iat: number;
  exp: number;
  typ: 'access';
}

export async function signAccessToken(secret: string, claims: Omit<AccessClaims, 'typ'>): Promise<string> {
  const header = b64url(enc.encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' })));
  const payload = b64url(enc.encode(JSON.stringify({ ...claims, typ: 'access' })));
  const sig = b64url(await hmacSha256(secret, `${header}.${payload}`));
  return `${header}.${payload}.${sig}`;
}

export async function verifyAccessToken(secret: string, token: string, nowSec: number): Promise<AccessClaims | null> {
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const [h, p, s] = parts as [string, string, string];
  try {
    const header = JSON.parse(dec.decode(b64urlDecode(h)));
    if (header.alg !== 'HS256') return null; // reject alg=none / algorithm confusion
    const expected = b64url(await hmacSha256(secret, `${h}.${p}`));
    if (!timingSafeEqual(expected, s)) return null;
    const claims = JSON.parse(dec.decode(b64urlDecode(p))) as AccessClaims;
    if (claims.typ !== 'access' || typeof claims.exp !== 'number' || claims.exp <= nowSec) return null;
    return claims;
  } catch {
    return null;
  }
}
