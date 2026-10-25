/**
 * Algerian phone-number normalisation to E.164 (+213XXXXXXXXX).
 *
 * Accepted inputs: 0550123456, 550123456, 213550123456, +213550123456, 00213550123456,
 * with optional spaces, dots, dashes or parentheses.
 * Mobile numbers start with 5, 6 or 7 (9 national digits). Landlines (2, 3, 4) are
 * accepted as 8 national digits so that association landlines can be stored for contact info,
 * but only mobile numbers are allowed for account registration.
 */
export function normalizeAlgerianPhone(input: string): string | null {
  if (typeof input !== 'string') return null;
  let s = input.trim().replace(/[\s.\-()]/g, '');
  // Convert Arabic-Indic and Eastern Arabic digits to ASCII.
  s = s.replace(/[٠-٩]/g, (d) => String(d.charCodeAt(0) - 0x0660))
       .replace(/[۰-۹]/g, (d) => String(d.charCodeAt(0) - 0x06f0));
  if (s.startsWith('+')) s = s.slice(1);
  else if (s.startsWith('00')) s = s.slice(2);
  if (!/^\d+$/.test(s)) return null;

  let national: string;
  if (s.startsWith('213')) national = s.slice(3);
  else if (s.startsWith('0')) national = s.slice(1);
  else national = s;
  if (national.startsWith('0')) national = national.slice(1); // "+2130550..." typo tolerance

  if (/^[567]\d{8}$/.test(national)) return `+213${national}`;
  if (/^[234]\d{7}$/.test(national)) return `+213${national}`;
  return null;
}

export function isAlgerianMobile(e164: string): boolean {
  return /^\+213[567]\d{8}$/.test(e164);
}

/** Telegram sends phone numbers with or without '+'. Only Algerian numbers are relevant here. */
export function normalizeTelegramPhone(p: string): string | null {
  return normalizeAlgerianPhone(p.startsWith('+') ? p : `+${p}`);
}

/** Masks a phone number for logs/admin views: +213 5** ** 34 56 → "+2135*****456". */
export function maskPhone(e164: string): string {
  if (e164.length < 8) return '***';
  return e164.slice(0, 5) + '*'.repeat(e164.length - 8) + e164.slice(-3);
}
