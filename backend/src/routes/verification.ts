import type { Ctx } from '../env';
import { conflict, forbidden, HttpError, json, notFound, unauthorized } from '../lib/http';
import { randomToken, sha256Hex, timingSafeEqual, uuid } from '../lib/crypto';
import { rateLimit } from '../lib/ratelimit';
import { requireUser } from '../lib/rbac';
import { verificationAudit } from '../lib/audit';
import { maskPhone, normalizeTelegramPhone } from '../lib/phone';
import { flushChannelOutbox } from '../services/channel';
import { sendMessage } from '../services/telegramApi';
import { handleDeviceDecision } from './deviceRequests';

const MAX_CONTACT_ATTEMPTS = 3;
const TOKEN_RE = /^[A-Za-z0-9_-]{32,64}$/;

// ───────────────────────── App-facing endpoints ─────────────────────────

/** Creates a short-lived, single-use verification session and returns the bot deep link. */
export async function startTelegramVerification(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  if (!ctx.env.TELEGRAM_BOT_USERNAME || !ctx.env.TELEGRAM_BOT_TOKEN) throw new HttpError(503, 'telegram_not_configured');
  await rateLimit(ctx.env.DB, `tgstart:${me.id}`, 6, 3600, ctx.now);
  const user = await ctx.env.DB.prepare(`SELECT phone_verified_at FROM users WHERE id = ? AND status = 'active'`)
    .bind(me.id).first<{ phone_verified_at: number | null }>();
  if (!user) throw unauthorized();
  if (user.phone_verified_at) throw conflict('already_verified');

  const token = randomToken(32); // 43 URL-safe chars, allowed in Telegram `start` parameter
  const id = uuid();
  const ttl = parseInt(ctx.env.VERIFICATION_TTL_SECONDS || '600', 10) * 1000;
  await ctx.env.DB.batch([
    // Only one live session per user: older ones are cancelled.
    ctx.env.DB.prepare(
      `UPDATE telegram_verifications SET status = 'cancelled' WHERE user_id = ? AND status IN ('pending','awaiting_contact')`,
    ).bind(me.id),
    ctx.env.DB.prepare(
      `INSERT INTO telegram_verifications (id, user_id, token_hash, status, created_at, expires_at) VALUES (?, ?, ?, 'pending', ?, ?)`,
    ).bind(id, me.id, await sha256Hex(token), ctx.now, ctx.now + ttl),
    verificationAudit(ctx.env.DB, ctx.now, { verificationId: id, userId: me.id, event: 'started' }),
  ]);
  const bot = ctx.env.TELEGRAM_BOT_USERNAME;
  return json({
    verificationId: id,
    deepLink: `https://t.me/${bot}?start=${token}`,
    appLink: `tg://resolve?domain=${bot}&start=${token}`,
    expiresAt: ctx.now + ttl,
  }, 201);
}

export async function getTelegramVerification(ctx: Ctx): Promise<Response> {
  const me = requireUser(ctx);
  const v = await ctx.env.DB.prepare(
    `SELECT id, user_id, status, failure_reason, expires_at, consumed_at, attempts FROM telegram_verifications WHERE id = ?`,
  ).bind(ctx.params.id).first<{ id: string; user_id: string; status: string; failure_reason: string | null; expires_at: number; consumed_at: number | null; attempts: number }>();
  // Same response for "missing" and "someone else's" to avoid IDOR probing.
  if (!v || v.user_id !== me.id) throw notFound();
  let status = v.status;
  if ((status === 'pending' || status === 'awaiting_contact') && v.expires_at <= ctx.now) status = 'expired';
  return json({
    verificationId: v.id,
    status,
    failureReason: v.failure_reason,
    attemptsLeft: Math.max(0, MAX_CONTACT_ATTEMPTS - v.attempts),
    expiresAt: v.expires_at,
    verifiedAt: v.consumed_at,
  });
}

// ───────────────────────── Telegram webhook ─────────────────────────

interface TgUser { id: number; is_bot?: boolean; language_code?: string }
interface TgMessage {
  message_id: number;
  from?: TgUser;
  chat: { id: number; type: string };
  text?: string;
  contact?: { phone_number: string; user_id?: number; first_name?: string };
  forward_origin?: unknown;
  forward_from?: unknown;
  forward_date?: number;
  via_bot?: unknown;
}
interface TgChatMember {
  chat: { id: number; type: string; title?: string };
  from: TgUser;
  new_chat_member: { status: string; user: TgUser; can_post_messages?: boolean };
}
interface TgUpdate { update_id: number; message?: TgMessage; my_chat_member?: TgChatMember; channel_post?: { chat: { id: number; type: string; title?: string } }; callback_query?: Parameters<typeof handleDeviceDecision>[1] }

type Lang = 'ar' | 'fr';
const T = {
  ar: {
    askContact: 'مرحباً 👋\nلتأكيد رقم هاتفك في تطبيق الجمعية، اضغط على الزر أدناه لمشاركة رقمك عبر تيليغرام.\n\nملاحظة: يجب أن يكون الرقم المسجّل في التطبيق هو نفسه رقم حساب تيليغرام هذا.',
    button: '📱 مشاركة رقم هاتفي',
    invalidLink: 'رابط التأكيد غير صالح. يرجى العودة إلى التطبيق والضغط على "تأكيد رقم الهاتف عبر تيليغرام" من جديد.',
    expired: 'انتهت صلاحية رابط التأكيد. يرجى إنشاء رابط جديد من التطبيق.',
    otherAccount: 'هذا الرابط مرتبط بحساب تيليغرام آخر. يرجى إنشاء رابط جديد من التطبيق.',
    useButton: 'يرجى استخدام زر "مشاركة رقم هاتفي" أدناه. لا يمكن قبول الأرقام المكتوبة يدوياً.',
    foreign: 'لا يمكن قبول جهة اتصال تخص شخصاً آخر. يرجى مشاركة رقمك الخاص باستخدام الزر.',
    forwarded: 'لا يمكن قبول جهة اتصال مُعاد توجيهها. يرجى استخدام الزر لمشاركة رقمك.',
    mismatch: 'الرقم لا يطابق الرقم المسجل. يرجى مراجعته والمحاولة مجدداً.',
    tooMany: 'تم تجاوز عدد المحاولات المسموح بها. يرجى مراجعة رقم الهاتف في التطبيق ثم إنشاء رابط جديد.',
    linkedElsewhere: 'حساب تيليغرام هذا مرتبط مسبقاً بحساب آخر في التطبيق.',
    success: 'تم تأكيد رقم هاتفك بنجاح ✅\nيمكنك الآن العودة إلى التطبيق.',
    already: 'تمت معالجة هذا الطلب مسبقاً. يمكنك العودة إلى التطبيق.',
    noSession: 'لا يوجد طلب تأكيد نشط. افتح التطبيق واضغط على "تأكيد رقم الهاتف عبر تيليغرام".',
  },
  fr: {
    askContact: 'Bonjour 👋\nPour confirmer votre numéro dans l’application de l’association, appuyez sur le bouton ci-dessous pour partager votre numéro via Telegram.\n\nLe numéro enregistré dans l’application doit être celui de ce compte Telegram.',
    button: '📱 Partager mon numéro',
    invalidLink: 'Lien de confirmation invalide. Revenez dans l’application et relancez la confirmation.',
    expired: 'Le lien de confirmation a expiré. Veuillez en générer un nouveau depuis l’application.',
    otherAccount: 'Ce lien est associé à un autre compte Telegram. Veuillez en générer un nouveau depuis l’application.',
    useButton: 'Veuillez utiliser le bouton « Partager mon numéro ». Les numéros saisis manuellement ne sont pas acceptés.',
    foreign: 'Le contact d’une autre personne ne peut pas être accepté. Partagez votre propre numéro avec le bouton.',
    forwarded: 'Un contact transféré ne peut pas être accepté. Utilisez le bouton pour partager votre numéro.',
    mismatch: 'Le numéro ne correspond pas au numéro enregistré. Veuillez le vérifier et réessayer.',
    tooMany: 'Nombre maximal de tentatives atteint. Vérifiez votre numéro dans l’application puis générez un nouveau lien.',
    linkedElsewhere: 'Ce compte Telegram est déjà associé à un autre compte de l’application.',
    success: 'Votre numéro de téléphone a été confirmé avec succès ✅\nVous pouvez revenir à l’application.',
    already: 'Cette demande a déjà été traitée. Vous pouvez revenir à l’application.',
    noSession: 'Aucune demande de confirmation active. Ouvrez l’application et lancez la confirmation via Telegram.',
  },
} as const;

const contactKeyboard = (lang: Lang) => ({
  keyboard: [[{ text: T[lang].button, request_contact: true }]],
  resize_keyboard: true,
  one_time_keyboard: true,
  is_persistent: false,
});
const removeKeyboard = { remove_keyboard: true };

interface VerificationRow {
  id: string; user_id: string; status: string; telegram_user_id: number | null; attempts: number; expires_at: number;
  phone_e164: string; locale: Lang; phone_verified_at: number | null;
}

export async function telegramWebhook(ctx: Ctx): Promise<Response> {
  const secret = ctx.env.TELEGRAM_WEBHOOK_SECRET;
  const provided = ctx.req.headers.get('x-telegram-bot-api-secret-token') ?? '';
  if (!secret || !timingSafeEqual(provided, secret)) throw forbidden('bad_webhook_secret');

  let update: TgUpdate;
  try {
    update = (await ctx.req.json()) as TgUpdate;
  } catch {
    return json({ ok: true }); // malformed: acknowledge so Telegram doesn't retry forever
  }
  if (typeof update?.update_id !== 'number') return json({ ok: true });

  // Idempotency: Telegram retries deliveries; each update is processed at most once.
  const fresh = await ctx.env.DB.prepare('INSERT INTO telegram_updates (update_id, received_at) VALUES (?, ?) ON CONFLICT DO NOTHING')
    .bind(update.update_id, ctx.now).run();
  if (!fresh.meta.changes) return json({ ok: true, duplicate: true });

  if (update.callback_query) {
    await handleDeviceDecision(ctx, update.callback_query);
    return json({ ok: true });
  }

  if (update.channel_post) {
    // Any post in a channel where the bot is admin also reveals the channel id.
    const c = update.channel_post.chat;
    await recordChannelMembership(ctx, { chat: c, from: { id: 0 }, new_chat_member: { status: 'administrator', user: { id: 0 }, can_post_messages: true } });
    return json({ ok: true });
  }

  if (update.my_chat_member) {
    await recordChannelMembership(ctx, update.my_chat_member);
    return json({ ok: true });
  }

  const msg = update.message;
  if (!msg?.from || msg.from.is_bot || msg.chat.type !== 'private') return json({ ok: true });

  if (msg.text?.trim() === '/id') {
    // Lets the owner find their Telegram id to be configured as an approver.
    await verificationAudit(ctx.env.DB, ctx.now, { telegramUserId: msg.from.id, event: 'id_request' }).run();
    await sendMessage(ctx, msg.chat.id, `معرّفك في تيليغرام: ${msg.from.id}`);
    return json({ ok: true });
  }

  if (msg.contact) await handleContact(ctx, msg);
  else if (msg.text?.startsWith('/start')) await handleStart(ctx, msg);
  else await handleOther(ctx, msg);
  return json({ ok: true });
}

const guessLang = (u: TgUser): Lang => (u.language_code?.startsWith('fr') ? 'fr' : 'ar');

async function handleStart(ctx: Ctx, msg: TgMessage): Promise<void> {
  const from = msg.from!;
  const token = msg.text!.split(/\s+/)[1] ?? '';
  let lang = guessLang(from);
  if (!TOKEN_RE.test(token)) {
    await sendMessage(ctx, msg.chat.id, T[lang].noSession, removeKeyboard);
    return;
  }
  const v = await ctx.env.DB.prepare(
    `SELECT v.id, v.user_id, v.status, v.telegram_user_id, v.attempts, v.expires_at, u.phone_e164, u.locale, u.phone_verified_at
     FROM telegram_verifications v JOIN users u ON u.id = v.user_id WHERE v.token_hash = ?`,
  ).bind(await sha256Hex(token)).first<VerificationRow>();
  if (!v) {
    await verificationAudit(ctx.env.DB, ctx.now, { telegramUserId: from.id, event: 'invalid_token' }).run();
    await sendMessage(ctx, msg.chat.id, T[lang].invalidLink, removeKeyboard);
    return;
  }
  lang = v.locale;
  const live = v.status === 'pending' || v.status === 'awaiting_contact';
  if (!live || v.expires_at <= ctx.now) {
    if (live) await ctx.env.DB.prepare(`UPDATE telegram_verifications SET status = 'expired' WHERE id = ? AND status IN ('pending','awaiting_contact')`).bind(v.id).run();
    await verificationAudit(ctx.env.DB, ctx.now, { verificationId: v.id, userId: v.user_id, telegramUserId: from.id, event: v.status === 'verified' ? 'replay_after_use' : 'expired_or_closed' }).run();
    await sendMessage(ctx, msg.chat.id, v.status === 'verified' ? T[lang].already : T[lang].expired, removeKeyboard);
    return;
  }
  if (v.telegram_user_id !== null && v.telegram_user_id !== from.id) {
    await verificationAudit(ctx.env.DB, ctx.now, { verificationId: v.id, userId: v.user_id, telegramUserId: from.id, event: 'token_used_by_other_telegram_account' }).run();
    await sendMessage(ctx, msg.chat.id, T[lang].otherAccount, removeKeyboard);
    return;
  }
  if (v.status === 'pending') {
    // Bind this session to the Telegram account that opened it (first opener wins).
    const bound = await ctx.env.DB.prepare(
      `UPDATE telegram_verifications SET status = 'awaiting_contact', telegram_user_id = ?, telegram_chat_id = ?
       WHERE id = ? AND status = 'pending' AND telegram_user_id IS NULL AND expires_at > ?`,
    ).bind(from.id, msg.chat.id, v.id, ctx.now).run();
    if (!bound.meta.changes) {
      await sendMessage(ctx, msg.chat.id, T[lang].otherAccount, removeKeyboard);
      return;
    }
    await verificationAudit(ctx.env.DB, ctx.now, { verificationId: v.id, userId: v.user_id, telegramUserId: from.id, event: 'bound' }).run();
  }
  await sendMessage(ctx, msg.chat.id, T[lang].askContact, contactKeyboard(lang));
}

async function liveSessionFor(ctx: Ctx, telegramUserId: number): Promise<VerificationRow | null> {
  return ctx.env.DB.prepare(
    `SELECT v.id, v.user_id, v.status, v.telegram_user_id, v.attempts, v.expires_at, u.phone_e164, u.locale, u.phone_verified_at
     FROM telegram_verifications v JOIN users u ON u.id = v.user_id
     WHERE v.telegram_user_id = ? AND v.status = 'awaiting_contact' AND u.status = 'active'
     ORDER BY v.created_at DESC LIMIT 1`,
  ).bind(telegramUserId).first<VerificationRow>();
}

async function recordFailure(ctx: Ctx, v: VerificationRow, reason: string, tgUserId: number, detail?: string): Promise<boolean> {
  const attempts = v.attempts + 1;
  const final = attempts >= MAX_CONTACT_ATTEMPTS;
  await ctx.env.DB.batch([
    ctx.env.DB.prepare(
      `UPDATE telegram_verifications SET attempts = ?, failure_reason = ?, status = ? WHERE id = ? AND status = 'awaiting_contact'`,
    ).bind(attempts, reason, final ? 'failed' : 'awaiting_contact', v.id),
    verificationAudit(ctx.env.DB, ctx.now, { verificationId: v.id, userId: v.user_id, telegramUserId: tgUserId, event: reason, detail }),
  ]);
  return final;
}

async function handleContact(ctx: Ctx, msg: TgMessage): Promise<void> {
  const from = msg.from!;
  const contact = msg.contact!;
  const v = await liveSessionFor(ctx, from.id);
  if (!v) {
    await sendMessage(ctx, msg.chat.id, T[guessLang(from)].noSession, removeKeyboard);
    return;
  }
  const lang = v.locale;
  if (v.expires_at <= ctx.now) {
    await ctx.env.DB.prepare(`UPDATE telegram_verifications SET status = 'expired' WHERE id = ? AND status = 'awaiting_contact'`).bind(v.id).run();
    await verificationAudit(ctx.env.DB, ctx.now, { verificationId: v.id, userId: v.user_id, telegramUserId: from.id, event: 'expired' }).run();
    await sendMessage(ctx, msg.chat.id, T[lang].expired, removeKeyboard);
    return;
  }
  // A contact shared via the request_contact button is never forwarded and carries the sender's own user_id.
  if (msg.forward_origin || msg.forward_from || msg.forward_date || msg.via_bot) {
    const final = await recordFailure(ctx, v, 'forwarded_contact', from.id);
    await sendMessage(ctx, msg.chat.id, final ? T[lang].tooMany : T[lang].forwarded, final ? removeKeyboard : contactKeyboard(lang));
    return;
  }
  if (contact.user_id !== from.id) {
    const final = await recordFailure(ctx, v, 'foreign_contact', from.id);
    await sendMessage(ctx, msg.chat.id, final ? T[lang].tooMany : T[lang].foreign, final ? removeKeyboard : contactKeyboard(lang));
    return;
  }
  const shared = normalizeTelegramPhone(contact.phone_number);
  if (!shared || shared !== v.phone_e164) {
    const final = await recordFailure(ctx, v, 'phone_mismatch', from.id, shared ? `shared=${maskPhone(shared)}` : 'shared=non_algerian');
    await sendMessage(ctx, msg.chat.id, final ? T[lang].tooMany : T[lang].mismatch, final ? removeKeyboard : contactKeyboard(lang));
    return;
  }
  if (v.phone_verified_at) {
    await sendMessage(ctx, msg.chat.id, T[lang].already, removeKeyboard);
    return;
  }
  const otherLink = await ctx.env.DB.prepare(
    'SELECT user_id FROM telegram_account_links WHERE telegram_user_id = ? AND user_id <> ?',
  ).bind(from.id, v.user_id).first();
  if (otherLink) {
    await ctx.env.DB.batch([
      ctx.env.DB.prepare(`UPDATE telegram_verifications SET status = 'failed', failure_reason = 'telegram_already_linked' WHERE id = ?`).bind(v.id),
      verificationAudit(ctx.env.DB, ctx.now, { verificationId: v.id, userId: v.user_id, telegramUserId: from.id, event: 'telegram_already_linked' }),
    ]);
    await sendMessage(ctx, msg.chat.id, T[lang].linkedElsewhere, removeKeyboard);
    return;
  }

  // Atomic consumption: every statement after the first is guarded by the state the first one wrote,
  // and the whole batch is one transaction (a UNIQUE violation on the link rolls everything back).
  const db = ctx.env.DB;
  const consumed = `EXISTS (SELECT 1 FROM telegram_verifications WHERE id = ?1 AND status = 'verified' AND consumed_at = ?2)`;
  const dateStr = new Date(ctx.now).toLocaleString('fr-DZ', { timeZone: 'Africa/Algiers', dateStyle: 'short', timeStyle: 'short' });
  let results: D1Result[];
  try {
    results = await db.batch([
      db.prepare(
        `UPDATE telegram_verifications SET status = 'verified', consumed_at = ?2, failure_reason = NULL
         WHERE id = ?1 AND status = 'awaiting_contact' AND telegram_user_id = ?3 AND expires_at > ?2`,
      ).bind(v.id, ctx.now, from.id),
      db.prepare(
        `UPDATE users SET phone_verified_at = ?2, phone_verify_method = 'telegram', updated_at = ?2
         WHERE id = ?3 AND phone_e164 = ?4 AND phone_verified_at IS NULL AND ${consumed}`,
      ).bind(v.id, ctx.now, v.user_id, v.phone_e164),
      db.prepare(
        `INSERT INTO telegram_account_links (user_id, telegram_user_id, telegram_chat_id, verification_id, linked_at)
         SELECT ?3, ?4, ?5, ?1, ?2 WHERE ${consumed}`,
      ).bind(v.id, ctx.now, v.user_id, from.id, msg.chat.id),
      db.prepare(
        `INSERT INTO verification_audit (verification_id, user_id, telegram_user_id, event, created_at)
         SELECT ?1, ?3, ?4, 'verified', ?2 WHERE ${consumed}`,
      ).bind(v.id, ctx.now, v.user_id, from.id),
      // Channel notification (no phone number, no token). Count is computed inside the transaction.
      db.prepare(
        `INSERT INTO channel_outbox (dedupe_key, text, status, attempts, next_attempt_at, created_at)
         SELECT 'verified:' || ?1,
                '✅ تم توثيق حساب جديد' || char(10) ||
                'معرّف المستخدم: ' || ?3 || char(10) ||
                'الحالة: مؤكد' || char(10) ||
                'طريقة التحقق: Telegram' || char(10) ||
                'التاريخ: ' || ?4 || char(10) ||
                'إجمالي المستخدمين المؤكدين: ' || (SELECT COUNT(*) FROM users WHERE phone_verified_at IS NOT NULL AND status = 'active'),
                'pending', 0, ?2, ?2
         WHERE ${consumed}
         ON CONFLICT(dedupe_key) DO NOTHING`,
      ).bind(v.id, ctx.now, v.user_id, dateStr),
    ]);
  } catch (e) {
    await verificationAudit(db, ctx.now, { verificationId: v.id, userId: v.user_id, telegramUserId: from.id, event: 'link_conflict', detail: String(e).slice(0, 120) }).run();
    await db.prepare(`UPDATE telegram_verifications SET status = 'failed', failure_reason = 'telegram_already_linked' WHERE id = ? AND status = 'awaiting_contact'`).bind(v.id).run();
    await sendMessage(ctx, msg.chat.id, T[lang].linkedElsewhere, removeKeyboard);
    return;
  }
  if (!results[0]?.meta.changes) {
    await sendMessage(ctx, msg.chat.id, T[lang].already, removeKeyboard);
    return;
  }
  await sendMessage(ctx, msg.chat.id, T[lang].success, removeKeyboard);
  ctx.waitUntil(flushChannelOutbox(ctx.env, ctx.fetch, ctx.now, 3));
}

async function handleOther(ctx: Ctx, msg: TgMessage): Promise<void> {
  const v = await liveSessionFor(ctx, msg.from!.id);
  if (v && v.expires_at > ctx.now) {
    // Manually typed numbers (or any text) are never accepted as proof.
    await sendMessage(ctx, msg.chat.id, T[v.locale].useButton, contactKeyboard(v.locale));
    if (msg.text && /\d{6,}/.test(msg.text.replace(/\D/g, ''))) {
      await verificationAudit(ctx.env.DB, ctx.now, { verificationId: v.id, userId: v.user_id, telegramUserId: msg.from!.id, event: 'typed_number_rejected' }).run();
    }
    return;
  }
  await sendMessage(ctx, msg.chat.id, T[guessLang(msg.from!)].noSession, removeKeyboard);
}

/** Housekeeping: expire stale sessions and drop old update IDs. */
export async function expireVerifications(db: D1Database, now: number): Promise<void> {
  await db.batch([
    db.prepare(`UPDATE telegram_verifications SET status = 'expired' WHERE status IN ('pending','awaiting_contact') AND expires_at <= ?`).bind(now),
    db.prepare('DELETE FROM telegram_updates WHERE received_at < ?').bind(now - 7 * 86_400_000),
  ]);
}


/**
 * When the bot is made an administrator of a channel, remember the channel id (private setting)
 * so the operator does not have to look it up. It is only *used* once copied into the
 * TELEGRAM_ADMIN_CHANNEL_ID secret.
 */
async function recordChannelMembership(ctx: Ctx, m: TgChatMember): Promise<void> {
  if (m.chat.type !== 'channel') return;
  const value = JSON.stringify({ id: m.chat.id, title: (m.chat.title ?? '').slice(0, 100), status: m.new_chat_member.status, canPost: !!m.new_chat_member.can_post_messages, at: ctx.now });
  await ctx.env.DB.prepare(
    `INSERT INTO application_settings (key, value, is_public, updated_at) VALUES ('telegram_detected_channel', ?, 0, ?)
     ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`,
  ).bind(value, ctx.now).run();
}
