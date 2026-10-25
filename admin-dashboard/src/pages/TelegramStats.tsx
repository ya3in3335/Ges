import { api } from '../lib/api';
import { Badge, Page, State, useLoad } from '../lib/ui';

interface Stats {
  byStatus: { status: string; n: number }[]; byEvent: { event: string; n: number }[]; verifiedPerDay: { day: string; n: number }[];
  channelOutbox: { status: string; n: number }[]; telegramConfigured: boolean; channelConfigured: boolean;
}
const STATUS: Record<string, string> = { pending: 'بانتظار الفتح', awaiting_contact: 'بانتظار مشاركة الرقم', verified: 'مؤكد', failed: 'فشل', expired: 'منتهي', cancelled: 'ملغى', sent: 'أُرسل' };
const EVENTS: Record<string, string> = {
  started: 'بدء طلب', bound: 'فتح البوت', verified: 'تحقق ناجح', phone_mismatch: 'رقم غير مطابق', foreign_contact: 'جهة اتصال لشخص آخر',
  forwarded_contact: 'جهة اتصال مُعاد توجيهها', typed_number_rejected: 'رقم مكتوب يدوياً', expired: 'انتهاء صلاحية', expired_or_closed: 'رابط منتهٍ/مغلق',
  invalid_token: 'رابط غير صالح', token_used_by_other_telegram_account: 'رابط من حساب تيليغرام آخر', telegram_already_linked: 'تيليغرام مرتبط بحساب آخر',
  replay_after_use: 'إعادة استخدام رابط', link_conflict: 'تعارض ربط', admin_reset: 'إلغاء من الإدارة',
};

export default function TelegramStats() {
  const { data, error, loading, reload } = useLoad(() => api.get<Stats>('/api/admin/telegram-stats'));
  const max = Math.max(1, ...(data?.verifiedPerDay.map((d) => d.n) ?? [1]));
  return (
    <Page title="إحصاءات التحقق عبر تيليغرام (30 يوماً)">
      <State loading={loading && !data} error={error} onRetry={reload} />
      {data && <>
        <p>البوت: {data.telegramConfigured ? <Badge tone="ok">مفعّل</Badge> : <Badge tone="bad">غير مهيأ</Badge>} — قناة الإدارة: {data.channelConfigured ? <Badge tone="ok">مفعّلة</Badge> : <Badge tone="warn">غير مهيأة</Badge>}</p>
        <div className="stats">{data.byStatus.map((s) => <div className="stat" key={s.status}><b>{s.n}</b><span>{STATUS[s.status] ?? s.status}</span></div>)}</div>
        <h2>عمليات التحقق الناجحة يومياً</h2>
        {data.verifiedPerDay.length === 0 ? <State empty /> : (
          <div className="bars" role="img" aria-label="رسم بياني للتحقق اليومي">
            {data.verifiedPerDay.map((d) => <div key={d.day} className="bar" title={`${d.day}: ${d.n}`}><i style={{ height: `${(d.n / max) * 100}%` }} /><small>{d.day.slice(5)}</small></div>)}
          </div>
        )}
        <h2>الأحداث</h2>
        <div className="table-wrap"><table><thead><tr><th>الحدث</th><th>العدد</th></tr></thead>
          <tbody>{data.byEvent.map((e) => <tr key={e.event}><td>{EVENTS[e.event] ?? e.event}</td><td>{e.n}</td></tr>)}</tbody></table></div>
        <h2>رسائل قناة الإدارة</h2>
        <p>{data.channelOutbox.map((s) => <Badge key={s.status} tone={s.status === 'failed' ? 'bad' : s.status === 'sent' ? 'ok' : 'warn'}>{STATUS[s.status] ?? s.status}: {s.n}</Badge>)}</p>
      </>}
    </Page>
  );
}
