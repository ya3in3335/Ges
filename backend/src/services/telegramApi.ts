import type { Ctx, Env } from '../env';

export interface TgResult { ok: boolean; description?: string; result?: unknown; retryAfter?: number }

/** Calls the Telegram Bot API. Never logs the bot token. */
export async function tgCall(env: Env, f: typeof fetch, method: string, body: Record<string, unknown>): Promise<TgResult> {
  if (!env.TELEGRAM_BOT_TOKEN) return { ok: false, description: 'telegram_not_configured' };
  try {
    const res = await f(`https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/${method}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    });
    const data = (await res.json().catch(() => ({}))) as { ok?: boolean; description?: string; result?: unknown; parameters?: { retry_after?: number } };
    return { ok: !!data.ok, description: data.description, result: data.result, retryAfter: data.parameters?.retry_after };
  } catch (e) {
    return { ok: false, description: e instanceof Error ? e.message : 'network_error' };
  }
}

export function sendMessage(ctx: Pick<Ctx, 'env' | 'fetch'>, chatId: number | string, text: string, replyMarkup?: unknown): Promise<TgResult> {
  return tgCall(ctx.env, ctx.fetch, 'sendMessage', {
    chat_id: chatId,
    text,
    ...(replyMarkup ? { reply_markup: replyMarkup } : {}),
  });
}
