import { useState } from 'react';
import { api, errorText } from '../lib/api';
import { dt } from '../lib/format';
import { Badge, Field, Modal, Page, Pager, State, useLoad } from '../lib/ui';

interface V { id: string; full_name: string; contact: string; skills: string; availability: string; preferred_areas: string | null; experience: string | null; status: string; review_note: string | null; created_at: number; phone_verified_at: number | null }
const STATUS: Record<string, [string, 'warn' | 'ok' | 'bad']> = { pending: ['قيد المراجعة', 'warn'], approved: ['مقبول', 'ok'], rejected: ['مرفوض', 'bad'] };

export default function Volunteers() {
  const [status, setStatus] = useState('pending');
  const [q, setQ] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  const { data, error, loading, reload } = useLoad(
    () => api.get<{ items: V[]; hasMore: boolean }>(`/api/admin/volunteers?page=${page}${status ? `&status=${status}` : ''}${query ? `&q=${encodeURIComponent(query)}` : ''}`),
    [page, status, query],
  );
  const [open, setOpen] = useState<V | null>(null);
  const [note, setNote] = useState('');
  const [activityId, setActivityId] = useState('');
  const [msg, setMsg] = useState<string | null>(null);
  const acts = useLoad(() => api.get<{ items: { id: string; titleAr: string }[] }>('/api/activities?phase=current'));

  const review = async (s: string) => {
    if (!open) return;
    try { await api.patch(`/api/admin/volunteers/${open.id}`, { status: s, note: note || null }); setOpen(null); reload(); } catch (e) { setMsg(errorText(e)); }
  };
  const assign = async () => {
    if (!open || !activityId) return;
    try { await api.post(`/api/admin/volunteers/${open.id}/assignments`, { activityId }); setMsg('تم الإسناد'); } catch (e) { setMsg(errorText(e)); }
  };

  return (
    <Page title="المتطوعون">
      <form className="filters" onSubmit={(e) => { e.preventDefault(); setPage(1); setQuery(q); }}>
        <select value={status} onChange={(e) => { setPage(1); setStatus(e.target.value); }}><option value="">الكل</option>{Object.entries(STATUS).map(([k, [v]]) => <option key={k} value={k}>{v}</option>)}</select>
        <input placeholder="بحث بالاسم أو المهارة" value={q} onChange={(e) => setQ(e.target.value)} />
        <button className="btn">بحث</button>
      </form>
      <State loading={loading && !data} error={error} empty={data?.items.length === 0} onRetry={reload} />
      {data && data.items.length > 0 && <div className="table-wrap"><table>
        <thead><tr><th>الاسم</th><th>المهارات</th><th>التوفر</th><th>الحالة</th><th>التاريخ</th><th /></tr></thead>
        <tbody>{data.items.map((v) => <tr key={v.id}>
          <td>{v.full_name} {v.phone_verified_at && <Badge tone="ok">مؤكد</Badge>}</td><td>{v.skills}</td><td>{v.availability}</td>
          <td><Badge tone={STATUS[v.status]![1]}>{STATUS[v.status]![0]}</Badge></td><td>{dt(v.created_at)}</td>
          <td><button className="btn ghost" onClick={() => { setOpen(v); setNote(v.review_note ?? ''); setMsg(null); }}>فتح</button></td>
        </tr>)}</tbody></table></div>}
      {data && <Pager page={page} hasMore={data.hasMore} onPage={setPage} />}

      {open && <Modal title={open.full_name} onClose={() => setOpen(null)}>
        <dl className="details">
          <dt>التواصل</dt><dd dir="ltr">{open.contact}</dd>
          <dt>المهارات</dt><dd>{open.skills}</dd>
          <dt>التوفر</dt><dd>{open.availability}</dd>
          <dt>المجالات</dt><dd>{open.preferred_areas ?? '—'}</dd>
          <dt>الخبرة</dt><dd>{open.experience ?? '—'}</dd>
        </dl>
        <div className="form">
          <Field label="ملاحظة للمتطوع (اختيارية)"><textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)} /></Field>
          <div className="row"><button className="btn" onClick={() => review('approved')}>قبول</button><button className="btn ghost danger" onClick={() => review('rejected')}>رفض</button></div>
          {open.status === 'approved' && <div className="row">
            <select value={activityId} onChange={(e) => setActivityId(e.target.value)}><option value="">اختر نشاطاً للإسناد</option>{acts.data?.items.map((a) => <option key={a.id} value={a.id}>{a.titleAr}</option>)}</select>
            <button className="btn ghost" onClick={assign}>إسناد</button>
          </div>}
          {msg && <div className="state">{msg}</div>}
        </div>
      </Modal>}
    </Page>
  );
}
