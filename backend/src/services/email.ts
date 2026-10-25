import type { Env } from '../env';

/** Optional e-mail delivery via Resend's HTTP API (free tier available). Returns false when not configured. */
export async function sendEmail(env: Env, f: typeof fetch, to: string, subject: string, text: string): Promise<boolean> {
  if (!env.RESEND_API_KEY || !env.EMAIL_FROM) return false;
  const res = await f('https://api.resend.com/emails', {
    method: 'POST',
    headers: { authorization: `Bearer ${env.RESEND_API_KEY}`, 'content-type': 'application/json' },
    body: JSON.stringify({ from: env.EMAIL_FROM, to: [to], subject, text }),
  });
  return res.ok;
}

export const emailConfigured = (env: Env) => !!(env.RESEND_API_KEY && env.EMAIL_FROM);
