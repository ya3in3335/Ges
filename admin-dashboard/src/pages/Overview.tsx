import { api } from '../lib/api';
import { Page, State, useLoad } from '../lib/ui';

const CARDS: [string, string][] = [
  ['users', 'المستخدمون النشطون'], ['verifiedUsers', 'أرقام مؤكدة عبر تيليغرام'], ['newUsersLast7Days', 'تسجيلات آخر 7 أيام'],
  ['pendingVolunteers', 'طلبات تطوع قيد المراجعة'], ['approvedVolunteers', 'متطوعون مقبولون'], ['activePolls', 'تصويتات جارية'],
  ['upcomingActivities', 'نشاطات قادمة/جارية'], ['publishedNews', 'أخبار منشورة'], ['verificationsLast24h', 'طلبات تحقق (24 ساعة)'],
  ['channelBacklog', 'رسائل قناة معلّقة'],
];

export default function Overview() {
  const { data, error, loading, reload } = useLoad(() => api.get<Record<string, number>>('/api/admin/overview'));
  return (
    <Page title="نظرة عامة" actions={<button className="btn ghost" onClick={reload}>تحديث</button>}>
      <State loading={loading && !data} error={error} onRetry={reload} />
      {data && <div className="stats">{CARDS.map(([k, l]) => <div className="stat" key={k}><b>{data[k] ?? 0}</b><span>{l}</span></div>)}</div>}
    </Page>
  );
}
