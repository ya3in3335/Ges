import { describe, expect, it } from 'vitest';
import { isAlgerianMobile, maskPhone, normalizeAlgerianPhone, normalizeTelegramPhone } from '../src/lib/phone';

describe('Algerian phone normalisation', () => {
  it.each([
    ['0550123456', '+213550123456'],
    ['550123456', '+213550123456'],
    ['213550123456', '+213550123456'],
    ['+213550123456', '+213550123456'],
    ['00213 550 12 34 56', '+213550123456'],
    ['+213 0550-12-34-56', '+213550123456'],
    ['0661 23 45 67', '+213661234567'],
    ['0770.12.34.56', '+213770123456'],
    ['٠٥٥٠١٢٣٤٥٦', '+213550123456'],
    ['041234567', '+21341234567'],
  ])('%s → %s', (input, out) => expect(normalizeAlgerianPhone(input)).toBe(out));

  it.each(['', 'abc', '0850123456', '05501234', '055012345678', '+33612345678', '+2135501234567'])('rejects %s', (input) => {
    expect(normalizeAlgerianPhone(input)).toBeNull();
  });

  it('distinguishes mobile numbers', () => {
    expect(isAlgerianMobile('+213550123456')).toBe(true);
    expect(isAlgerianMobile('+21341234567')).toBe(false);
  });

  it('normalises Telegram contact numbers with and without plus', () => {
    expect(normalizeTelegramPhone('213550123456')).toBe('+213550123456');
    expect(normalizeTelegramPhone('+213550123456')).toBe('+213550123456');
    expect(normalizeTelegramPhone('33612345678')).toBeNull();
  });

  it('masks phone numbers', () => {
    expect(maskPhone('+213550123456')).toBe('+2135*****456');
  });
});
