const fmt = new Intl.DateTimeFormat('ar-DZ-u-nu-latn', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Africa/Algiers' });
export const dt = (ms: number | null | undefined) => (ms ? fmt.format(new Date(ms)) : '—');

/** <input type="datetime-local"> works in local time; the API uses epoch milliseconds. */
export const toInput = (ms: number | null | undefined) => {
  if (!ms) return '';
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
};
export const fromInput = (s: string) => (s ? new Date(s).getTime() : 0);

export const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: 'مشرف عام', ADMIN: 'مشرف', EDITOR: 'محرر', VOLUNTEER: 'متطوع', USER: 'عضو',
};
