import { useState } from 'react';
import { api, errorText } from '../lib/api';
import { dt, fromInput, toInput } from '../lib/format';
import { Badge, Field, Modal, Page, Pager, State, useLoad } from '../lib/ui';

interface Activity { id: string; titleAr: string; titleFr: string | null; descriptionAr: string; descriptionFr: string | null; location: string | null; latitude: number | null; longitude: number | null; startsAt: number; endsAt: number; capacity: number | null; registeredCount: number; imageUrl: string | null; videoUrl: string | null; isCampaign: boolean; featured: boolean; status: string; registrationOpen: boolean; phase: string }
interface Participant { user_id: string; full_name: string; phone_e164: string; email: string; status: string; created_at: number }
const STATUS: Record<string, string> = { draft: 'مسودة', published: 'منشور', cancelled: 'ملغى', archived: 'مؤرشف' };
const PHASE: Record<string, string> = { upcoming: 'قادم', ongoing: 'جارٍ', completed: 'منتهٍ', cancelled: 'ملغى' };

type Form = { id?: string; titleAr: string; titleFr: string; descriptionAr: string; descriptionFr: string; location: string; latitude: string; longitude: string; startsAt: string; endsAt: string; capacity: string; imageUrl: string; videoUrl: string; isCampaign: boolean; featured: boolean; status: string; registrationOpen: boolean; notify: boolean };
const empty: Form = { titleAr: '', titleFr: '', descriptionAr: '', descriptionFr: '', location: '', latitude: '', longitude: '', startsAt: '', endsAt: '', capacity: '', imageUrl: '', videoUrl: '', isCampaign: false, featured: false, status: 'draft', registrationOpen: true, notify: false };

export default function Activities() {
  const [page, setPage] = useState(1);
  const { data, error, loading, reload } = useLoad(() => api.get<{ items: Activity[]; hasMore: boolean }>(`/api/admin/activities?page=${page}`), [page]);
  const [edit, setEdit] = useState<Form | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [people, setPeople] = useState<{ activity: Activity; items: Participant[] } | null>(null);

  const save = async () => {
    if (!edit) return;
    setErr(null);
    const num = (s: string) => (s.trim() === '' ? null : Number(s));
    const body = {
      titleAr: edit.titleAr, titleFr: edit.titleFr || null, descriptionAr: edit.descriptionAr, descriptionFr: edit.descriptionFr || null,
      location: edit.location || null, latitude: num(edit.latitude), longitude: num(edit.longitude), startsAt: fromInput(edit.startsAt), endsAt: fromInput(edit.endsAt),
      capacity: num(edit.capacity), imageUrl: edit.imageUrl || null, videoUrl: edit.videoUrl || null, isCampaign: edit.isCampaign, featured: edit.featured,
      status: edit.status, registrationOpen: edit.registrationOpen, notify: edit.notify,
    };
    try {
      if (edit.id) await api.patch(`/api/admin/activities/${edit.id}`, body); else await api.post('/api/admin/activities', body);
      setEdit(null); reload();
    } catch (e) { setErr(errorText(e)); }
  };

  const openPeople = async (a: Activity) => setPeople({ activity: a, items: (await api.get<{ items: Participant[] }>(`/api/admin/activities/${a.id}/participants`)).items });
  const setPart = async (uid: string, status: string) => {
    if (!people) return;
    await api.patch(`/api/admin/activities/${people.activity.id}/participants/${uid}`, { status });
    openPeople(people.activity);
  };

  const toForm = (a: Activity): Form => ({
    id: a.id, titleAr: a.titleAr, titleFr: a.titleFr ?? '', descriptionAr: a.descriptionAr, descriptionFr: a.descriptionFr ?? '', location: a.location ?? '',
    latitude: a.latitude?.toString() ?? '', longitude: a.longitude?.toString() ?? '', startsAt: toInput(a.startsAt), endsAt: toInput(a.endsAt),
    capacity: a.capacity?.toString() ?? '', imageUrl: a.imageUrl ?? '', videoUrl: a.videoUrl ?? '', isCampaign: a.isCampaign, featured: a.featured,
    status: a.status, registrationOpen: a.registrationOpen, notify: false,
  });

  return (
    <Page title="النشاطات والحملات" actions={<button className="btn" onClick={() => setEdit({ ...empty })}>+ نشاط جديد</button>}>
      <State loading={loading && !data} error={error} empty={data?.items.length === 0} onRetry={reload} />
      {data && data.items.length > 0 && <div className="table-wrap"><table>
        <thead><tr><th>العنوان</th><th>الموعد</th><th>المرحلة</th><th>الحالة</th><th>المسجلون</th><th /></tr></thead>
        <tbody>{data.items.map((a) => <tr key={a.id}>
          <td>{a.isCampaign && <Badge tone="warn">حملة</Badge>} {a.titleAr}</td><td>{dt(a.startsAt)}</td><td>{PHASE[a.phase]}</td>
          <td><Badge tone={a.status === 'published' ? 'ok' : 'neutral'}>{STATUS[a.status]}</Badge></td>
          <td>{a.registeredCount}{a.capacity ? ` / ${a.capacity}` : ''}</td>
          <td className="row-actions"><button className="btn ghost" onClick={() => setEdit(toForm(a))}>تعديل</button><button className="btn ghost" onClick={() => openPeople(a)}>المشاركون</button></td>
        </tr>)}</tbody></table></div>}
      {data && <Pager page={page} hasMore={data.hasMore} onPage={setPage} />}

      {edit && <Modal title={edit.id ? 'تعديل نشاط' : 'نشاط جديد'} onClose={() => setEdit(null)}>
        <div className="form">
          <Field label="العنوان (عربية) *"><input value={edit.titleAr} onChange={(e) => setEdit({ ...edit, titleAr: e.target.value })} /></Field>
          <Field label="Titre (français)"><input dir="ltr" value={edit.titleFr} onChange={(e) => setEdit({ ...edit, titleFr: e.target.value })} /></Field>
          <Field label="الوصف (عربية) *"><textarea rows={5} value={edit.descriptionAr} onChange={(e) => setEdit({ ...edit, descriptionAr: e.target.value })} /></Field>
          <Field label="Description (français)"><textarea dir="ltr" rows={3} value={edit.descriptionFr} onChange={(e) => setEdit({ ...edit, descriptionFr: e.target.value })} /></Field>
          <div className="row">
            <Field label="البداية *"><input type="datetime-local" value={edit.startsAt} onChange={(e) => setEdit({ ...edit, startsAt: e.target.value })} /></Field>
            <Field label="النهاية *"><input type="datetime-local" value={edit.endsAt} onChange={(e) => setEdit({ ...edit, endsAt: e.target.value })} /></Field>
          </div>
          <Field label="المكان"><input value={edit.location} onChange={(e) => setEdit({ ...edit, location: e.target.value })} /></Field>
          <div className="row">
            <Field label="خط العرض"><input dir="ltr" value={edit.latitude} onChange={(e) => setEdit({ ...edit, latitude: e.target.value })} /></Field>
            <Field label="خط الطول"><input dir="ltr" value={edit.longitude} onChange={(e) => setEdit({ ...edit, longitude: e.target.value })} /></Field>
            <Field label="عدد المقاعد"><input type="number" min={1} value={edit.capacity} onChange={(e) => setEdit({ ...edit, capacity: e.target.value })} /></Field>
          </div>
          <Field label="رابط الصورة"><input dir="ltr" value={edit.imageUrl} onChange={(e) => setEdit({ ...edit, imageUrl: e.target.value })} /></Field>
          <Field label="رابط فيديو (https)"><input dir="ltr" value={edit.videoUrl} onChange={(e) => setEdit({ ...edit, videoUrl: e.target.value })} /></Field>
          <Field label="الحالة"><select value={edit.status} onChange={(e) => setEdit({ ...edit, status: e.target.value })}>{Object.entries(STATUS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></Field>
          <label className="check"><input type="checkbox" checked={edit.isCampaign} onChange={(e) => setEdit({ ...edit, isCampaign: e.target.checked })} /> حملة خيرية (تظهر في صفحة التبرعات)</label>
          <label className="check"><input type="checkbox" checked={edit.featured} onChange={(e) => setEdit({ ...edit, featured: e.target.checked })} /> مميز</label>
          <label className="check"><input type="checkbox" checked={edit.registrationOpen} onChange={(e) => setEdit({ ...edit, registrationOpen: e.target.checked })} /> التسجيل مفتوح</label>
          {!edit.id && <label className="check"><input type="checkbox" checked={edit.notify} onChange={(e) => setEdit({ ...edit, notify: e.target.checked })} /> إرسال إشعار</label>}
          {err && <div className="state error">{err}</div>}
          <button className="btn" onClick={save}>حفظ</button>
        </div>
      </Modal>}

      {people && <Modal title={`المشاركون — ${people.activity.titleAr}`} onClose={() => setPeople(null)}>
        {people.items.length === 0 ? <State empty /> : <div className="table-wrap"><table>
          <thead><tr><th>الاسم</th><th>الهاتف</th><th>الحالة</th><th /></tr></thead>
          <tbody>{people.items.map((p) => <tr key={p.user_id}><td>{p.full_name}</td><td><bdi dir="ltr">{p.phone_e164}</bdi></td><td>{p.status}</td>
            <td className="row-actions"><button className="btn ghost" onClick={() => setPart(p.user_id, 'attended')}>حضر</button><button className="btn ghost" onClick={() => setPart(p.user_id, 'cancelled')}>إلغاء</button></td></tr>)}</tbody>
        </table></div>}
      </Modal>}
    </Page>
  );
}
