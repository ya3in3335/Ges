/**
 * Minimal SMTP client over Cloudflare TCP sockets (implicit TLS, port 465), enough to send one
 * message with AUTH PLAIN — e.g. through a Gmail account with an app password.
 */
export interface SmtpSocket {
  readable: ReadableStream<Uint8Array>;
  writable: WritableStream<Uint8Array>;
  close(): Promise<void>;
}
export type SmtpConnect = (host: string, port: number) => SmtpSocket;

export interface SmtpConfig { host: string; port: number; user: string; password: string }
export interface SmtpMail { fromEmail: string; fromName?: string; to: string; subject: string; text: string; html?: string }

const enc = new TextEncoder();
const b64 = (s: string) => {
  let bin = '';
  for (const b of enc.encode(s)) bin += String.fromCharCode(b);
  return btoa(bin);
};
const wrap = (s: string) => s.replace(/.{1,76}/g, '$&\r\n');
const header = (s: string) => (/^[\x20-\x7e]*$/.test(s) ? s : `=?UTF-8?B?${b64(s)}?=`);
const clean = (s: string) => s.replace(/[\r\n<>]/g, '');

/** RFC 5322 message, multipart/alternative when HTML is given; bodies are base64 (safe for UTF-8 and dot-stuffing). */
export function buildMessage(m: SmtpMail, now = new Date(), id = crypto.randomUUID()): string {
  const from = m.fromName ? `${header(m.fromName)} <${clean(m.fromEmail)}>` : `<${clean(m.fromEmail)}>`;
  const head = [
    `From: ${from}`,
    `To: <${clean(m.to)}>`,
    `Subject: ${header(m.subject)}`,
    `Date: ${now.toUTCString().replace('GMT', '+0000')}`,
    `Message-ID: <${id}@${clean(m.fromEmail.split('@')[1] ?? 'localhost')}>`,
    'MIME-Version: 1.0',
  ];
  const part = (type: string, body: string) => `Content-Type: ${type}; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n${wrap(b64(body))}`;
  if (!m.html) return `${head.join('\r\n')}\r\n${part('text/plain', m.text)}`;
  const boundary = `b_${id.replace(/-/g, '')}`;
  return `${head.join('\r\n')}\r\nContent-Type: multipart/alternative; boundary="${boundary}"\r\n\r\n`
    + `--${boundary}\r\n${part('text/plain', m.text)}\r\n--${boundary}\r\n${part('text/html', m.html)}\r\n--${boundary}--\r\n`;
}

export async function smtpSend(connect: SmtpConnect, cfg: SmtpConfig, m: SmtpMail): Promise<void> {
  const socket = connect(cfg.host, cfg.port);
  const writer = socket.writable.getWriter();
  const reader = socket.readable.getReader();
  const dec = new TextDecoder();
  let buf = '';

  /** Reads one (possibly multi-line) reply and checks its code. */
  const expect = async (code: number) => {
    for (;;) {
      const lines = buf.split('\r\n');
      for (let i = 0; i < lines.length - 1; i++) {
        const line = lines[i]!;
        if (/^\d{3} /.test(line) || /^\d{3}$/.test(line)) {
          buf = lines.slice(i + 1).join('\r\n');
          if (Number(line.slice(0, 3)) !== code) throw new Error(`smtp_${line.slice(0, 3)}`);
          return;
        }
      }
      const { value, done } = await reader.read();
      if (done) throw new Error('smtp_closed');
      buf += dec.decode(value, { stream: true });
    }
  };
  const send = (line: string) => writer.write(enc.encode(`${line}\r\n`));

  try {
    await expect(220);
    await send('EHLO association-api'); await expect(250);
    await send(`AUTH PLAIN ${b64(`\0${cfg.user}\0${cfg.password}`)}`); await expect(235);
    await send(`MAIL FROM:<${clean(m.fromEmail)}>`); await expect(250);
    await send(`RCPT TO:<${clean(m.to)}>`); await expect(250);
    await send('DATA'); await expect(354);
    await writer.write(enc.encode(`${buildMessage(m)}\r\n.\r\n`)); await expect(250);
    await send('QUIT');
  } finally {
    await socket.close().catch(() => {});
  }
}
