import { describe, expect, it } from 'vitest';
import { buildMessage, smtpSend, type SmtpConnect } from '../src/services/smtp';

/** Fake SMTP server: answers each command in order and records what the client sent. */
function fakeServer(replies: string[]) {
  const sent: string[] = [];
  let push: (s: string) => void = () => {};
  const readable = new ReadableStream<Uint8Array>({ start(c) { push = (s) => c.enqueue(new TextEncoder().encode(s)); } });
  const writable = new WritableStream<Uint8Array>({
    write(chunk) {
      const text = new TextDecoder().decode(chunk);
      sent.push(text);
      const next = replies.shift();
      if (next) queueMicrotask(() => push(next));
    },
  });
  const connect: SmtpConnect = (host, port) => {
    expect([host, port]).toEqual(['smtp.gmail.com', 465]);
    queueMicrotask(() => push(replies.shift()!));
    return { readable, writable, close: async () => {} };
  };
  return { connect, sent };
}

const cfg = { host: 'smtp.gmail.com', port: 465, user: 'me@gmail.com', password: 'abcd efgh ijkl mnop' };
const mail = { fromEmail: 'me@gmail.com', fromName: 'الجمعية', to: 'member@example.com', subject: 'رمز التأكيد', text: 'الرمز 123456', html: '<b>123456</b>' };

describe('smtp', () => {
  it('runs the full conversation with AUTH PLAIN', async () => {
    const s = fakeServer(['220 ready\r\n', '250-smtp.gmail.com\r\n250-AUTH PLAIN\r\n250 SMTPUTF8\r\n', '235 ok\r\n', '250 ok\r\n', '250 ok\r\n', '354 go\r\n', '250 queued\r\n', '221 bye\r\n']);
    await smtpSend(s.connect, cfg, mail);
    expect(s.sent[0]).toBe('EHLO association-api\r\n');
    expect(atob(s.sent[1]!.slice('AUTH PLAIN '.length).trim())).toBe('\0me@gmail.com\0abcd efgh ijkl mnop');
    expect(s.sent[2]).toBe('MAIL FROM:<me@gmail.com>\r\n');
    expect(s.sent[3]).toBe('RCPT TO:<member@example.com>\r\n');
    expect(s.sent[5]!.endsWith('\r\n.\r\n')).toBe(true);
    expect(s.sent.at(-1)).toBe('QUIT\r\n');
  });

  it('fails on a rejected login', async () => {
    const s = fakeServer(['220 ready\r\n', '250 hi\r\n', '535 5.7.8 Username and Password not accepted\r\n']);
    await expect(smtpSend(s.connect, cfg, mail)).rejects.toThrow('smtp_535');
  });

  it('builds a UTF-8 multipart message that survives SMTP', () => {
    const msg = buildMessage(mail, new Date(Date.UTC(2026, 0, 1)), '00000000-0000-0000-0000-000000000000');
    expect(msg).toContain('Subject: =?UTF-8?B?');
    expect(msg).toContain('multipart/alternative');
    expect(msg.split('\r\n').every((l) => l.length <= 998 && !l.startsWith('.'))).toBe(true);
    const textPart = msg.split('base64\r\n\r\n')[1]!.split('\r\n--')[0]!.replace(/\r\n/g, '');
    expect(new TextDecoder().decode(Uint8Array.from(atob(textPart), (c) => c.charCodeAt(0)))).toBe('الرمز 123456');
  });
});
