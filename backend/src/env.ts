export interface Env {
  DB: D1Database;
  MEDIA?: R2Bucket;

  TELEGRAM_BOT_USERNAME: string;
  ALLOWED_ORIGINS: string;
  PUBLIC_BASE_URL: string;
  ACCESS_TOKEN_TTL_SECONDS: string;
  REFRESH_TOKEN_TTL_DAYS: string;
  VERIFICATION_TTL_SECONDS: string;
  EMAIL_FROM?: string;

  JWT_SECRET: string;
  TELEGRAM_BOT_TOKEN?: string;
  TELEGRAM_WEBHOOK_SECRET?: string;
  TELEGRAM_ADMIN_CHANNEL_ID?: string;
  /** Comma-separated Telegram user ids allowed to approve supervisor devices from the bot. */
  TELEGRAM_APPROVER_IDS?: string;
  FCM_SERVICE_ACCOUNT_JSON?: string;
  RESEND_API_KEY?: string;
}

export type Role = 'SUPER_ADMIN' | 'ADMIN' | 'EDITOR' | 'VOLUNTEER' | 'USER';

export interface AuthUser {
  id: string;
  role: Role;
  sessionId: string;
}

/** Per-request context passed to every handler. */
export interface Ctx {
  env: Env;
  req: Request;
  url: URL;
  params: Record<string, string>;
  user: AuthUser | null;
  ip: string;
  now: number;
  waitUntil: (p: Promise<unknown>) => void;
  /** Outbound fetch, injectable for tests. */
  fetch: typeof fetch;
}
