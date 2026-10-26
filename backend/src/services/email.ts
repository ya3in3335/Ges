import type { Env } from '../env';
import { smtpSend, type SmtpConnect } from './smtp';

/** Cloudflare's TCP socket API, loaded lazily so the module also runs under Node in tests. */
async function workerConnect(): Promise<SmtpConnect> {
  const mod = 'cloudflare:sockets';
  const { connect } = await import(/* @vite-ignore */ mod);
  return (host, port) => connect({ hostname: host, port }, { secureTransport: 'on', allowHalfOpen: false });
}

/**
 * Optional e-mail delivery. SMTP (e.g. a Gmail account with an app password: free, no domain) is
 * used when SMTP_USER/SMTP_PASSWORD are set; otherwise Brevo (free tier, works with a verified single sender such as a Gmail
 * address, no domain needed) is used when BREVO_API_KEY is set, otherwise Resend (needs a domain).
 * Returns false when no provider is configured or delivery was refused; success is never simulated.
 */
export async function sendEmail(env: Env, f: typeof fetch, to: string, subject: string, text: string, html?: string, connect?: SmtpConnect): Promise<boolean> {
  if (env.SMTP_USER && env.SMTP_PASSWORD) {
    try {
      await smtpSend(connect ?? (await workerConnect()), {
        host: env.SMTP_HOST || 'smtp.gmail.com', port: Number(env.SMTP_PORT || 465), user: env.SMTP_USER, password: env.SMTP_PASSWORD,
      }, { fromEmail: env.EMAIL_FROM || env.SMTP_USER, fromName: env.EMAIL_FROM_NAME || undefined, to, subject, text, html });
      return true;
    } catch (e) {
      console.error('smtp', e instanceof Error ? e.message : e);
      return false;
    }
  }
  if (!env.EMAIL_FROM) return false;
  if (env.BREVO_API_KEY) {
    const res = await f('https://api.brevo.com/v3/smtp/email', {
      method: 'POST',
      headers: { 'api-key': env.BREVO_API_KEY, 'content-type': 'application/json', accept: 'application/json' },
      body: JSON.stringify({
        sender: { email: env.EMAIL_FROM, ...(env.EMAIL_FROM_NAME ? { name: env.EMAIL_FROM_NAME } : {}) },
        to: [{ email: to }],
        subject,
        textContent: text,
        ...(html ? { htmlContent: html } : {}),
      }),
    });
    if (!res.ok) console.error('brevo', res.status, (await res.text()).slice(0, 200));
    return res.ok;
  }
  if (env.RESEND_API_KEY) {
    const from = env.EMAIL_FROM_NAME ? `${env.EMAIL_FROM_NAME} <${env.EMAIL_FROM}>` : env.EMAIL_FROM;
    const res = await f('https://api.resend.com/emails', {
      method: 'POST',
      headers: { authorization: `Bearer ${env.RESEND_API_KEY}`, 'content-type': 'application/json' },
      body: JSON.stringify({ from, to: [to], subject, text, ...(html ? { html } : {}) }),
    });
    return res.ok;
  }
  return false;
}

export const emailConfigured = (env: Env) =>
  !!(env.SMTP_USER && env.SMTP_PASSWORD) || !!((env.BREVO_API_KEY || env.RESEND_API_KEY) && env.EMAIL_FROM);

/** Bilingual message carrying a 6-digit code (plain text + simple HTML that renders right-to-left). */
export function codeEmail(kind: 'verify' | 'reset', code: string, minutes: number) {
  const t = kind === 'verify'
    ? { subject: 'رمز تأكيد البريد الإلكتروني / Code de vérification', ar: 'رمز تأكيد بريدك الإلكتروني هو:', fr: 'Votre code de vérification est :' }
    : { subject: 'إعادة تعيين كلمة المرور / Réinitialisation du mot de passe', ar: 'رمز إعادة تعيين كلمة المرور هو:', fr: 'Votre code de réinitialisation est :' };
  const ar2 = `صالح لمدة ${minutes} دقيقة. إذا لم تطلب هذا الرمز فتجاهل هذه الرسالة.`;
  const fr2 = `Valable ${minutes} minutes. Si vous n’êtes pas à l’origine de cette demande, ignorez ce message.`;
  const noReply = { ar: 'هذه رسالة آلية، يُرجى عدم الرد عليها.', fr: 'Message automatique, merci de ne pas y répondre.' };
  const text = `${t.ar} ${code}\n${ar2}\n\n${t.fr} ${code}\n${fr2}\n\n— ${noReply.ar} / ${noReply.fr}`;
  const html = `<div dir="rtl" style="font-family:Tahoma,Arial,sans-serif;max-width:480px;margin:auto;padding:24px;text-align:center">
<p style="font-size:16px">${t.ar}</p>
<p style="font-size:34px;letter-spacing:8px;font-weight:bold;margin:16px 0" dir="ltr">${code}</p>
<p style="color:#666;font-size:13px">${ar2}</p><hr style="border:none;border-top:1px solid #eee;margin:20px 0">
<div dir="ltr"><p style="font-size:14px">${t.fr} <b>${code}</b></p><p style="color:#666;font-size:12px">${fr2}</p></div>
<p style="color:#999;font-size:11px;margin-top:20px">${noReply.ar}<br><span dir="ltr">${noReply.fr}</span></p></div>`;
  return { subject: t.subject, text, html };
}
