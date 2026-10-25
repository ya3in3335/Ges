import { handle } from '../src/index';
import type { Env } from '../src/env';
import { migratedDb, type ShimD1 } from './d1shim';

export interface Sent { method: string; body: any }

export class TestApp {
  db: ShimD1 = migratedDb();
  now = Date.UTC(2026, 0, 15, 10, 0, 0);
  telegram: Sent[] = [];
  pending: Promise<unknown>[] = [];
  env: Env;
  private ipCounter = 1;

  constructor(overrides: Partial<Env> = {}) {
    this.env = {
      DB: this.db as unknown as D1Database,
      TELEGRAM_BOT_USERNAME: 'TestAssocBot',
      ALLOWED_ORIGINS: 'https://admin.example.test',
      PUBLIC_BASE_URL: 'https://api.example.test',
      ACCESS_TOKEN_TTL_SECONDS: '900',
      REFRESH_TOKEN_TTL_DAYS: '30',
      VERIFICATION_TTL_SECONDS: '600',
      JWT_SECRET: 'test-secret-test-secret-test-secret-0123',
      TELEGRAM_BOT_TOKEN: '123:abc',
      TELEGRAM_WEBHOOK_SECRET: 'webhook-secret-xyz',
      TELEGRAM_ADMIN_CHANNEL_ID: '-100123',
      ...overrides,
    };
  }

  fetchMock: typeof fetch = async (input, init) => {
    const url = String(input);
    const m = url.match(/api\.telegram\.org\/bot[^/]+\/(\w+)/);
    if (m) {
      this.telegram.push({ method: m[1]!, body: JSON.parse(String(init?.body ?? '{}')) });
      return new Response(JSON.stringify({ ok: true, result: {} }), { headers: { 'content-type': 'application/json' } });
    }
    return new Response('{}', { status: 404 });
  };

  /** Each test client gets a distinct IP unless specified, so IP rate limits don't interfere. */
  freshIp() { return `10.0.0.${this.ipCounter++}`; }

  async req(method: string, path: string, opts: { body?: unknown; token?: string; headers?: Record<string, string>; ip?: string } = {}) {
    const headers: Record<string, string> = { 'cf-connecting-ip': opts.ip ?? '10.9.9.9', ...(opts.headers ?? {}) };
    if (opts.body !== undefined) headers['content-type'] = 'application/json';
    if (opts.token) headers.authorization = `Bearer ${opts.token}`;
    const res = await handle(
      new Request(`https://api.example.test${path}`, { method, headers, body: opts.body === undefined ? undefined : JSON.stringify(opts.body) }),
      this.env,
      { waitUntil: (p) => { this.pending.push(p); } },
      this.fetchMock,
      this.now,
    );
    await Promise.all(this.pending.splice(0));
    const text = await res.text();
    return { status: res.status, body: text ? JSON.parse(text) : null, headers: res.headers };
  }

  async register(i: number, extra: Partial<{ phone: string; email: string }> = {}) {
    const phone = extra.phone ?? `0550${String(100000 + i).slice(-6)}`;
    const r = await this.req('POST', '/api/auth/register', {
      ip: this.freshIp(),
      body: { fullName: `مستخدم تجريبي ${'ابجدهوزحطي'[i % 10]}`, email: extra.email ?? `user${i}@example.com`, phone, password: 'Secret123x', confirmPassword: 'Secret123x' },
    });
    if (r.status !== 201) throw new Error(`register failed ${r.status} ${JSON.stringify(r.body)}`);
    return { token: r.body.tokens.accessToken as string, refresh: r.body.tokens.refreshToken as string, id: r.body.user.id as string, phone: r.body.user.phone as string };
  }

  setRole(userId: string, role: string) {
    this.db.raw.prepare('UPDATE users SET role = ? WHERE id = ?').run(role, userId);
  }

  markVerified(userId: string) {
    this.db.raw.prepare('UPDATE users SET phone_verified_at = ? WHERE id = ?').run(this.now, userId);
  }

  update = 1000;
  async tg(message: Record<string, unknown>, opts: { secret?: string; updateId?: number } = {}) {
    return this.req('POST', '/telegram/webhook', {
      headers: { 'x-telegram-bot-api-secret-token': opts.secret ?? 'webhook-secret-xyz' },
      body: { update_id: opts.updateId ?? this.update++, message: { message_id: 1, date: 0, ...message } },
    });
  }
}

export const tgUser = (id: number) => ({ from: { id, is_bot: false, first_name: 'T' }, chat: { id, type: 'private' } });
