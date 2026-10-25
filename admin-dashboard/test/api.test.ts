import { describe, expect, it, vi } from 'vitest';
import { ApiClient, ApiError, atLeast } from '../src/lib/api';

function memoryStore() {
  const m = new Map<string, string>();
  return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), removeItem: (k: string) => void m.delete(k), m };
}
const res = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

describe('admin API client', () => {
  it('logs in as admin client and keeps only the refresh token in storage', async () => {
    const store = memoryStore();
    const f = vi.fn(async () => res(200, { user: { id: 'u', role: 'ADMIN' }, tokens: { accessToken: 'A', refreshToken: 'R' } }));
    const c = new ApiClient('https://api.test', store, f as never);
    await c.login('a@b.c', 'pw');
    expect(JSON.parse((f.mock.calls[0] as unknown as [string, RequestInit])[1].body as string).client).toBe('admin');
    expect([...store.m.values()]).toEqual(['R']);
  });

  it('refreshes once on 401 and retries with the new token', async () => {
    const store = memoryStore();
    store.setItem('adm_rt', 'R1');
    const calls: string[] = [];
    const f = vi.fn(async (url: string, init: RequestInit) => {
      calls.push(`${url} ${(init.headers as Record<string, string>).authorization ?? ''}`);
      if (url.endsWith('/api/auth/refresh')) return res(200, { tokens: { accessToken: 'A2', refreshToken: 'R2' } });
      if ((init.headers as Record<string, string>).authorization === 'Bearer A2') return res(200, { ok: 1 });
      return res(401, { error: 'unauthorized' });
    });
    const c = new ApiClient('https://api.test', store, f as never);
    expect(await c.get('/api/admin/overview')).toEqual({ ok: 1 });
    expect(store.getItem('adm_rt')).toBe('R2');
    expect(calls.filter((x) => x.includes('/refresh'))).toHaveLength(1);
  });

  it('ends the session when refresh fails', async () => {
    const store = memoryStore();
    store.setItem('adm_rt', 'R1');
    const f = vi.fn(async () => res(401, { error: 'refresh_token_reused' }));
    const c = new ApiClient('https://api.test', store, f as never);
    const ended = vi.fn();
    c.onSessionEnd = ended;
    await expect(c.get('/api/admin/users')).rejects.toBeInstanceOf(ApiError);
    expect(store.getItem('adm_rt')).toBeNull();
    expect(ended).toHaveBeenCalled();
  });

  it('ranks roles', () => {
    expect(atLeast('ADMIN', 'EDITOR')).toBe(true);
    expect(atLeast('EDITOR', 'ADMIN')).toBe(false);
    expect(atLeast(undefined, 'USER')).toBe(false);
  });
});
