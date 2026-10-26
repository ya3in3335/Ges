import { describe, expect, it, vi } from 'vitest';
import { ApiClient } from '../src/lib/api';
import { lectureProblems, registrationsCsv } from '../src/pages/Lectures';

const res = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
function memoryStore() {
  const m = new Map<string, string>();
  return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), removeItem: (k: string) => void m.delete(k), m };
}

describe('desktop activation', () => {
  it('activates with a code and keeps the device session', async () => {
    const store = memoryStore();
    const f = vi.fn(async () => res(201, { user: { id: 'u', role: 'ADMIN' }, tokens: { accessToken: 'A', refreshToken: 'R' } }));
    const c = new ApiClient('https://api.test', store, f as never);
    const u = await c.activate('12345678', 'Windows - PC');
    expect(u.role).toBe('ADMIN');
    const [url, init] = f.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('https://api.test/api/auth/activate');
    expect(JSON.parse(init.body as string)).toEqual({ code: '12345678', deviceName: 'Windows - PC' });
    expect(c.hasSession).toBe(true);
  });

  it('requests approval through the bot and stores the session once approved', async () => {
    const store = memoryStore();
    let approved = false;
    const f = vi.fn(async (_url: string, init: RequestInit = {}) => {
      if (init.method === 'POST') return res(201, { requestId: 'r1', secret: 's'.repeat(30) });
      expect((init.headers as Record<string, string>)['x-request-secret']).toBe('s'.repeat(30));
      return res(200, approved ? { status: 'approved', user: { id: 'u', role: 'EDITOR' }, tokens: { accessToken: 'A', refreshToken: 'R' } } : { status: 'pending' });
    });
    const c = new ApiClient('https://api.test', store, f as never);
    const r = await c.requestActivation('محمد', 'Windows - PC');
    expect((await c.pollActivation(r.requestId, r.secret)).status).toBe('pending');
    expect(c.hasSession).toBe(false);
    approved = true;
    expect((await c.pollActivation(r.requestId, r.secret)).status).toBe('approved');
    expect(c.hasSession).toBe(true);
  });
});

describe('lectures page', () => {
  const base = { kind: 'lecture', titleAr: 'محاضرة', titleFr: '', descriptionAr: '', speaker: '', location: '', onlineUrl: '', startsAt: '2026-11-01T10:00', endsAt: '2026-11-01T12:00', capacity: '', registrationOpen: true, status: 'published', notify: true };
  it('names each problem', () => {
    expect(lectureProblems(base)).toEqual([]);
    expect(lectureProblems({ ...base, titleAr: 'تس', endsAt: '2026-11-01T09:00', onlineUrl: 'http://x' })).toEqual([
      'العنوان (3 أحرف على الأقل)', 'النهاية يجب أن تكون بعد البداية', 'الرابط يجب أن يبدأ بـ https://',
    ]);
  });
  it('exports a spreadsheet-safe CSV with a BOM for Excel', () => {
    const csv = registrationsCsv([{ userId: 'u', status: 'registered', firstName: '=cmd', lastName: 'علي', phone: '+213555', email: null, gender: 'male', birthYear: 1990, wilaya: '31 - وهران', maritalStatus: 'married', childrenCount: 2, occupation: 'worker', occupationDetail: 'أستاذ', note: 'قال "مرحبا"', createdAt: 0 }]);
    expect(csv.startsWith('﻿')).toBe(true);
    expect(csv).toContain(`"'=cmd"`);
    expect(csv).toContain('"قال ""مرحبا"""');
    expect(csv).toContain('"متزوج"');
  });
});
