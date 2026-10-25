import { b64url, b64urlDecode, randomBytes, timingSafeEqual } from './crypto';

// PBKDF2-HMAC-SHA256 via WebCrypto. 100 000 iterations is the maximum Cloudflare Workers
// allow for PBKDF2; the format stores the parameters so they can be raised later.
const ITERATIONS = 100_000;
const enc = new TextEncoder();

async function derive(password: string, salt: Uint8Array, iterations: number): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey('raw', enc.encode(password.normalize('NFKC')), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations }, key, 256);
  return new Uint8Array(bits);
}

export async function hashPassword(password: string): Promise<string> {
  const salt = randomBytes(16);
  const dk = await derive(password, salt, ITERATIONS);
  return `pbkdf2_sha256$${ITERATIONS}$${b64url(salt)}$${b64url(dk)}`;
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const [algo, iterStr, saltStr, hashStr] = stored.split('$');
  if (algo !== 'pbkdf2_sha256' || !iterStr || !saltStr || !hashStr) return false;
  const dk = await derive(password, b64urlDecode(saltStr), parseInt(iterStr, 10));
  return timingSafeEqual(b64url(dk), hashStr);
}

/** A pre-computed hash used to equalise timing when the account does not exist. */
export const DUMMY_HASH = 'pbkdf2_sha256$100000$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';

/** Minimum policy: 8+ chars, at least one letter and one digit, not trivially common. */
export function passwordPolicyError(pw: string): string | null {
  if (pw.length < 8) return 'password_too_short';
  if (pw.length > 128) return 'password_too_long';
  if (!/\p{L}/u.test(pw) || !/\d/.test(pw)) return 'password_too_weak';
  const common = ['password1', 'azerty123', 'qwerty123', '12345678a', 'abc12345', 'algerie1'];
  if (common.includes(pw.toLowerCase())) return 'password_too_common';
  return null;
}
