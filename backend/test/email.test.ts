import { describe, expect, it } from 'vitest';
import { TestApp } from './helpers';

function withBrevo() {
  const app = new TestApp({ BREVO_API_KEY: 'brevo-test', EMAIL_FROM: 'sender@example.com', EMAIL_FROM_NAME: 'الجمعية' });
  const mails: any[] = [];
  const base = app.fetchMock;
  app.fetchMock = async (input, init) => {
    if (String(input) === 'https://api.brevo.com/v3/smtp/email') {
      expect((init?.headers as Record<string, string>)['api-key']).toBe('brevo-test');
      mails.push(JSON.parse(String(init?.body)));
      return new Response('{"messageId":"x"}', { status: 201 });
    }
    return base(input, init);
  };
  const lastCode = () => /(\d{6})/.exec(mails.at(-1).textContent)![1]!;
  return { app, mails, lastCode };
}

describe('email verification', () => {
  it('is reported unavailable without a provider', async () => {
    const app = new TestApp();
    const u = await app.register(1);
    const r = await app.req('POST', '/api/auth/email/send', { token: u.token });
    expect(r.status).toBe(503);
    expect(r.body.error).toBe('email_not_configured');
  });

  it('sends a bilingual code through Brevo and verifies the account', async () => {
    const { app, mails, lastCode } = withBrevo();
    const u = await app.register(2, { email: 'member@example.com' });
    expect((await app.req('POST', '/api/auth/email/send', { token: u.token })).status).toBe(200);
    expect(mails[0].to).toEqual([{ email: 'member@example.com' }]);
    expect(mails[0].sender).toEqual({ email: 'sender@example.com', name: 'الجمعية' });
    expect(mails[0].htmlContent).toContain(lastCode());
    expect((await app.req('POST', '/api/auth/email/verify', { token: u.token, body: { code: '000000' === lastCode() ? '111111' : '000000' } })).status).toBe(400);
    expect((await app.req('POST', '/api/auth/email/verify', { token: u.token, body: { code: lastCode() } })).status).toBe(200);
    const me = await app.req('GET', '/api/me', { token: u.token });
    expect(me.body.user.emailVerified).toBe(true);
    expect((await app.req('POST', '/api/auth/email/send', { token: u.token })).body.alreadyVerified).toBe(true);
  });

  it('only the latest code is valid', async () => {
    const { app, lastCode } = withBrevo();
    const u = await app.register(3);
    await app.req('POST', '/api/auth/email/send', { token: u.token });
    const first = lastCode();
    await app.req('POST', '/api/auth/email/send', { token: u.token });
    const second = lastCode();
    if (first !== second) expect((await app.req('POST', '/api/auth/email/verify', { token: u.token, body: { code: first } })).status).toBe(400);
    expect((await app.req('POST', '/api/auth/email/verify', { token: u.token, body: { code: second } })).status).toBe(200);
  });

  it('limits guessing', async () => {
    const { app } = withBrevo();
    const u = await app.register(4);
    await app.req('POST', '/api/auth/email/send', { token: u.token });
    let last = 0;
    for (let i = 0; i < 11; i++) last = (await app.req('POST', '/api/auth/email/verify', { token: u.token, body: { code: String(100000 + i) } })).status;
    expect(last).toBe(429);
  });
});
