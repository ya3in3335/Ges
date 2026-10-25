import { useState } from 'react';
import { api, errorText, mediaUrl } from '../lib/api';
import { dt } from '../lib/format';
import { Badge, Field, Modal, Page, Pager, State, useLoad } from '../lib/ui';
import { useAuth } from '../App';

interface News { id: string; titleAr: string; titleFr: string | null; bodyAr: string; bodyFr: string | null; category: string; imageUrl: string | null; featured: boolean; status: string; publishedAt: number | null; updatedAt: number }
const CATS: Record<string, string> = { news: 'خبر', announcement: 'إعلان', campaign: 'حملة', report: 'تقرير' };
const STATUS: Record<string, string> = { draft: 'مسودة', published: 'منشور', archived: 'مؤرشف' };
const empty = { titleAr: '', titleFr: '', bodyAr: '', bodyFr: '', category: 'news', imageUrl: '', featured: false, status: 'draft', notify: false };

export default function NewsPage() {
  const { user } = useAuth();
  const [page, setPage] = useState(1);
  const [status, setStatus] = useState('');
  const { data, error, loading, reload } = useLoad(() => api.get<{ items: News[]; hasMore: boolean }>(`/api/admin/news?page=${page}${status ? `&status=${status}` : ''}`), [page, status]);
  const [edit, setEdit] = useState<(typeof empty & { id?: string }) | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const save = async () => {
    if (!edit) return;
    setErr(null);
    const body = { ...edit, titleFr: edit.titleFr || null, bodyFr: edit.bodyFr || null, imageUrl: edit.imageUrl || null };
    delete (body as { id?: string }).id;
    try {
      if (edit.id) await api.patch(`/api/admin/news/${edit.id}`, body); else await api.post('/api/admin/news', body);
      setEdit(null); await reload();
    } catch (e) { setErr(errorText(e)); }
  };

  return (
    <Page title="الأخبار والإعلانات" actions={<button className="btn" onClick={() => setEdit({ ...empty })}>+ خبر جديد</button>}>
      <div className="filters">
        <select value={status} onChange={(e) => { setPage(1); setStatus(e.target.value); }}>
          <option value="">كل الحالات</option>{Object.entries(STATUS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
      </div>
      <State loading={loading && !data} error={error} empty={data?.items.length === 0} onRetry={reload} />
      {data && data.items.length > 0 && <div className="table-wrap"><table>
        <thead><tr><th>العنوان</th><th>التصنيف</th><th>الحالة</th><th>النشر</th><th /></tr></thead>
        <tbody>{data.items.map((n) => <tr key={n.id}>
          <td>{n.featured && '★ '}{n.titleAr}</td><td>{CATS[n.category]}</td>
          <td><Badge tone={n.status === 'published' ? 'ok' : 'neutral'}>{STATUS[n.status]}</Badge></td><td>{dt(n.publishedAt)}</td>
          <td className="row-actions">
            <button className="btn ghost" onClick={() => setEdit({ ...empty, ...n, titleFr: n.titleFr ?? '', bodyFr: n.bodyFr ?? '', imageUrl: n.imageUrl ?? '', notify: false })}>تعديل</button>
            {user && ['ADMIN', 'SUPER_ADMIN'].includes(user.role) && <button className="btn ghost danger" onClick={async () => { if (confirm('حذف نهائي؟')) { await api.del(`/api/admin/news/${n.id}`); reload(); } }}>حذف</button>}
          </td></tr>)}</tbody></table></div>}
      {data && <Pager page={page} hasMore={data.hasMore} onPage={setPage} />}

      {edit && <Modal title={edit.id ? 'تعديل خبر' : 'خبر جديد'} onClose={() => setEdit(null)}>
        <div className="form">
          <Field label="العنوان (عربية) *"><input value={edit.titleAr} onChange={(e) => setEdit({ ...edit, titleAr: e.target.value })} /></Field>
          <Field label="Titre (français)"><input dir="ltr" value={edit.titleFr} onChange={(e) => setEdit({ ...edit, titleFr: e.target.value })} /></Field>
          <Field label="النص (عربية) *"><textarea rows={6} value={edit.bodyAr} onChange={(e) => setEdit({ ...edit, bodyAr: e.target.value })} /></Field>
          <Field label="Texte (français)"><textarea dir="ltr" rows={4} value={edit.bodyFr} onChange={(e) => setEdit({ ...edit, bodyFr: e.target.value })} /></Field>
          <div className="row">
            <Field label="التصنيف"><select value={edit.category} onChange={(e) => setEdit({ ...edit, category: e.target.value })}>{Object.entries(CATS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></Field>
            <Field label="الحالة"><select value={edit.status} onChange={(e) => setEdit({ ...edit, status: e.target.value })}>{Object.entries(STATUS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></Field>
          </div>
          <Field label="رابط الصورة (https أو /media/…)"><input dir="ltr" value={edit.imageUrl} onChange={(e) => setEdit({ ...edit, imageUrl: e.target.value })} /></Field>
          {edit.imageUrl && <img className="preview" src={mediaUrl(edit.imageUrl)} alt="" />}
          <label className="check"><input type="checkbox" checked={edit.featured} onChange={(e) => setEdit({ ...edit, featured: e.target.checked })} /> مميز في الصفحة الرئيسية</label>
          <label className="check"><input type="checkbox" checked={edit.notify} onChange={(e) => setEdit({ ...edit, notify: e.target.checked })} /> إرسال إشعار عند النشر</label>
          {err && <div className="state error">{err}</div>}
          <button className="btn" onClick={save}>حفظ</button>
        </div>
      </Modal>}
    </Page>
  );
}
