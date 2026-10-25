import type { Env } from '../env';
import { b64url } from './../lib/crypto';

/**
 * Firebase Cloud Messaging HTTP v1 sender. Requires FCM_SERVICE_ACCOUNT_JSON.
 * The OAuth access token is created from the service account with an RS256 JWT (WebCrypto).
 */
interface ServiceAccount { project_id: string; client_email: string; private_key: string; token_uri?: string }

let cachedToken: { token: string; exp: number } | null = null;

export const fcmConfigured = (env: Env) => !!env.FCM_SERVICE_ACCOUNT_JSON;

function pemToDer(pem: string): ArrayBuffer {
  const b64 = pem.replace(/-----[^-]+-----/g, '').replace(/\s+/g, '');
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out.buffer;
}

async function accessToken(sa: ServiceAccount, f: typeof fetch, nowMs: number): Promise<string> {
  if (cachedToken && cachedToken.exp > nowMs + 60_000) return cachedToken.token;
  const enc = new TextEncoder();
  const iat = Math.floor(nowMs / 1000);
  const header = b64url(enc.encode(JSON.stringify({ alg: 'RS256', typ: 'JWT' })));
  const claims = b64url(enc.encode(JSON.stringify({
    iss: sa.client_email,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: sa.token_uri ?? 'https://oauth2.googleapis.com/token',
    iat,
    exp: iat + 3600,
  })));
  const key = await crypto.subtle.importKey('pkcs8', pemToDer(sa.private_key), { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const sig = new Uint8Array(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, enc.encode(`${header}.${claims}`)));
  const res = await f(sa.token_uri ?? 'https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${header}.${claims}.${b64url(sig)}`,
  });
  if (!res.ok) throw new Error(`fcm_oauth_${res.status}`);
  const data = (await res.json()) as { access_token: string; expires_in: number };
  cachedToken = { token: data.access_token, exp: nowMs + data.expires_in * 1000 };
  return data.access_token;
}

export interface PushMessage { title: string; body: string; data?: Record<string, string> }

export async function sendPush(env: Env, f: typeof fetch, target: { topic: string } | { token: string }, m: PushMessage, nowMs: number): Promise<{ ok: boolean; error?: string; unregistered?: boolean }> {
  if (!env.FCM_SERVICE_ACCOUNT_JSON) return { ok: false, error: 'not_configured' };
  const sa = JSON.parse(env.FCM_SERVICE_ACCOUNT_JSON) as ServiceAccount;
  const token = await accessToken(sa, f, nowMs);
  const res = await f(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify({
      message: {
        ...target,
        notification: { title: m.title, body: m.body },
        data: m.data ?? {},
        android: { priority: 'high', notification: { channel_id: 'association_default' } },
      },
    }),
  });
  if (res.ok) return { ok: true };
  const text = await res.text();
  return { ok: false, error: `fcm_${res.status}`, unregistered: res.status === 404 || text.includes('UNREGISTERED') };
}
