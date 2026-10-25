import { ZodError, type ZodTypeAny, type infer as ZInfer } from 'zod';

export class HttpError extends Error {
  constructor(
    public status: number,
    public code: string,
    message?: string,
    public details?: unknown,
  ) {
    super(message ?? code);
  }
}

export const badRequest = (code: string, details?: unknown) => new HttpError(400, code, code, details);
export const unauthorized = (code = 'unauthorized') => new HttpError(401, code);
export const forbidden = (code = 'forbidden') => new HttpError(403, code);
export const notFound = (code = 'not_found') => new HttpError(404, code);
export const conflict = (code: string) => new HttpError(409, code);
export const tooMany = (retryAfter: number) => new HttpError(429, 'rate_limited', 'rate_limited', { retryAfter });

const SECURITY_HEADERS: Record<string, string> = {
  'content-type': 'application/json; charset=utf-8',
  'cache-control': 'no-store',
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'no-referrer',
  'x-frame-options': 'DENY',
};

export function json(data: unknown, status = 200, extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(data), { status, headers: { ...SECURITY_HEADERS, ...extra } });
}

export function errorResponse(err: unknown): Response {
  if (err instanceof HttpError) {
    const headers: Record<string, string> = {};
    if (err.status === 429 && typeof (err.details as { retryAfter?: number })?.retryAfter === 'number') {
      headers['retry-after'] = String((err.details as { retryAfter: number }).retryAfter);
    }
    return json({ error: err.code, details: err.details ?? null }, err.status, headers);
  }
  if (err instanceof ZodError) {
    return json({ error: 'validation_failed', details: err.issues.map((i) => ({ path: i.path.join('.'), code: i.code })) }, 400);
  }
  console.error('unhandled', err instanceof Error ? err.stack : err);
  return json({ error: 'internal_error' }, 500);
}

const MAX_JSON_BYTES = 64 * 1024;

export async function readJson<S extends ZodTypeAny>(req: Request, schema: S): Promise<ZInfer<S>> {
  const ct = req.headers.get('content-type') ?? '';
  if (!ct.toLowerCase().startsWith('application/json')) throw badRequest('expected_json');
  const text = await req.text();
  if (text.length > MAX_JSON_BYTES) throw new HttpError(413, 'payload_too_large');
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    throw badRequest('invalid_json');
  }
  return schema.parse(raw);
}

/** Parses page/pageSize query params with sane bounds. */
export function pagination(url: URL, maxSize = 50): { limit: number; offset: number; page: number } {
  const page = Math.max(1, Math.min(10_000, parseInt(url.searchParams.get('page') ?? '1', 10) || 1));
  const size = Math.max(1, Math.min(maxSize, parseInt(url.searchParams.get('pageSize') ?? '20', 10) || 20));
  return { limit: size, offset: (page - 1) * size, page };
}
