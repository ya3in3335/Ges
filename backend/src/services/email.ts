import type { Env } from '../env';

/**
 * Optional e-mail delivery. Brevo (free tier, works with a verified single sender such as a Gmail
 * address, no domain needed) is used when BREVO_API_KEY is set, otherwise Resend (needs a domain).
 * Returns false when no provider is configured or delivery was refused; success is never simulated.
 */
export async function sendEmail(env: Env, f: typeof fetch, to: string, subject: string, text: string, html?: string): Promise<boolean> {
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

export const emailConfigured = (env: Env) => !!((env.BREVO_API_KEY || env.RESEND_API_KEY) && env.EMAIL_FROM);

/** Bilingual message carrying a 6-digit code (plain text + simple HTML that renders right-to-left). */
export function codeEmail(kind: 'verify' | 'reset', code: string, minutes: number) {
  const t = kind === 'verify'
    ? { subject: 'رمز تأكيد البريد الإلكتروني / Code de vérification', ar: 'رمز تأكيد بريدك الإلكتروني هو:', fr: 'Votre code de vérification est :' }
    : { subject: 'إعادة تعيين كلمة المرور / Réinitialisation du mot de passe', ar: 'رمز إعادة تعيين كلمة المرور هو:', fr: 'Votre code de réinitialisation est :' };
  const ar2 = `صالح لمدة ${minutes} دقيقة. إذا لم تطلب هذا الرمز فتجاهل هذه الرسالة.`;
  const fr2 = `Valable ${minutes} minutes. Si vous n’êtes pas à l’origine de cette demande, ignorez ce message.`;
  const text = `${t.ar} ${code}\n${ar2}\n\n${t.fr} ${code}\n${fr2}`;
  const html = `<div dir="rtl" style="font-family:Tahoma,Arial,sans-serif;max-width:480px;margin:auto;padding:24px;text-align:center">
<p style="font-size:16px">${t.ar}</p>
<p style="font-size:34px;letter-spacing:8px;font-weight:bold;margin:16px 0" dir="ltr">${code}</p>
<p style="color:#666;font-size:13px">${ar2}</p><hr style="border:none;border-top:1px solid #eee;margin:20px 0">
<div dir="ltr"><p style="font-size:14px">${t.fr} <b>${code}</b></p><p style="color:#666;font-size:12px">${fr2}</p></div></div>`;
  return { subject: t.subject, text, html };
}
