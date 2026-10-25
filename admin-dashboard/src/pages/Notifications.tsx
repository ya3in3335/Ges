import { useState } from 'react';
import { api, errorText } from '../lib/api';
import { dt } from '../lib/format';
import { Badge, Field, Page, State, useLoad } from '../lib/ui';
import { useAuth } from '../App';

interface N { id: string; topic: string; title_ar: string; body_ar: string; target_user_id: string | null; delivery: string; delivery_error: string | null; created_at: number }
const TOPICS: Record<string, string> = { important: 'رسالة مهمة', news: 'أخبار', activities: 'نشاطات', polls: 'تصويت', volunteer: 'تطوع' };
const DELIVERY: Record<string, [string, 'ok' | 'warn' | 'bad' | 'neutral']> = { sent: ['أُرسل', 'ok'], queued: ['في الانتظار', 'warn'], failed: ['فشل', 'bad'], not_configured: ['FCM غير مهيأ', 'neutral'] };

export default function Notifications() {
  const { user } = useAuth();
  const { data, error, loading, reload } = useLoad(() => api.get<{ items: N[]; fcmConfigured: boolean }>('/api/admin/notifications'));
  const [f, setF] = useState({ topic: 'important', titleAr: '', titleFr: '', bodyAr: '', bodyFr: '' });
  const [msg, setMsg] = useState<string | null>(null);
  const canSend = user && ['ADMIN', 'SUPER_ADMIN'].includes(user.role);

  const send = async () => {
    setMsg(null);
    if (!confirm('إرسال الإشعار لجميع المشتركين؟')) return;
    try {
      const r = await api.post<{ delivery: string }>('/api/admin/notifications', { ...f, titleFr: f.titleFr || null, bodyFr: f.bodyFr || null });
      setMsg(`الحالة: ${DELIVERY[r.delivery]?.[0] ?? r.delivery}`); reload();
    } catch (e) { setMsg(errorText(e)); }
  };

  return (
    <Page title="الإشعارات">
      {data && !data.fcmConfigured && <p className="note">Firebase Cloud Messaging غير مهيأ: تُحفظ الإشعارات وتظهر داخل التطبيق فقط، ولا تُرسل كإشعارات فورية.</p>}
      {canSend && <div className="form card">
        <Field label="النوع"><select value={f.topic} onChange={(e) => setF({ ...f, topic: e.target.value })}>{Object.entries(TOPICS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></Field>
        <Field label="العنوان (عربية)"><input maxLength={120} value={f.titleAr} onChange={(e) => setF({ ...f, titleAr: e.target.value })} /></Field>
        <Field label="Titre (français)"><input dir="ltr" maxLength={120} value={f.titleFr} onChange={(e) => setF({ ...f, titleFr: e.target.value })} /></Field>
        <Field label="النص (عربية)"><textarea maxLength={500} rows={3} value={f.bodyAr} onChange={(e) => setF({ ...f, bodyAr: e.target.value })} /></Field>
        <Field label="Texte (français)"><textarea dir="ltr" maxLength={500} rows={2} value={f.bodyFr} onChange={(e) => setF({ ...f, bodyFr: e.target.value })} /></Field>
        {msg && <div className="state">{msg}</div>}
        <button className="btn" disabled={f.titleAr.length < 2 || f.bodyAr.length < 2} onClick={send}>إرسال</button>
      </div>}
      <h2>السجل</h2>
      <State loading={loading && !data} error={error} empty={data?.items.length === 0} onRetry={reload} />
      {data && data.items.length > 0 && <div className="table-wrap"><table>
        <thead><tr><th>التاريخ</th><th>النوع</th><th>العنوان</th><th>الوجهة</th><th>التسليم</th></tr></thead>
        <tbody>{data.items.map((n) => <tr key={n.id}>
          <td>{dt(n.created_at)}</td><td>{TOPICS[n.topic]}</td><td>{n.title_ar}</td><td>{n.target_user_id ? 'مستخدم واحد' : 'الجميع'}</td>
          <td><Badge tone={DELIVERY[n.delivery]?.[1] ?? 'neutral'}>{DELIVERY[n.delivery]?.[0] ?? n.delivery}</Badge>{n.delivery_error && <small> {n.delivery_error}</small>}</td>
        </tr>)}</tbody></table></div>}
    </Page>
  );
}
