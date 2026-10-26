import { useState } from 'react';
import { useAuth } from '../App';
import { api, atLeast, errorText } from '../lib/api';
import { saveText } from '../lib/desktop';
import { dt, fromInput, toInput } from '../lib/format';
import { Badge, Field, Modal, Page, State, useLoad } from '../lib/ui';

interface Lecture { id: string; kind: string; titleAr: string; titleFr: string | null; descriptionAr: string | null; speaker: string | null; location: string | null; onlineUrl: string | null; startsAt: number; endsAt: number; capacity: number | null; registeredCount: number; status: string; phase: string; registrationOpenFlag?: boolean; registrationOpen: boolean }
interface Registration { userId: string; status: string; firstName: string; lastName: string; phone: string; email: string | null; gender: string | null; birthYear: number | null; wilaya: string | null; maritalStatus: string | null; childrenCount: number | null; occupation: string | null; occupationDetail: string | null; note: string | null; createdAt: number }

export const KIND: Record<string, string> = { lecture: 'محاضرة', meeting: 'اجتماع', training: 'دورة تكوينية' };
const STATUS: Record<string, string> = { draft: 'مسودة', published: 'منشور', cancelled: 'ملغى' };
const GENDER: Record<string, string> = { male: 'ذكر', female: 'أنثى' };
const MARITAL: Record<string, string> = { single: 'أعزب', married: 'متزوج', divorced: 'مطلق', widowed: 'أرمل' };
const OCC: Record<string, string> = { university_student: 'طالب جامعي', pupil: 'متمدرس', worker: 'عامل', unemployed: 'بدون عمل', retired: 'متقاعد', other: 'أخرى' };
const REG: Record<string, string> = { registered: 'مسجّل', attended: 'حضر', cancelled: 'ملغى' };

type Form = { id?: string; kind: string; titleAr: string; titleFr: string; descriptionAr: string; speaker: string; location: string; onlineUrl: string; startsAt: string; endsAt: string; capacity: string; registrationOpen: boolean; status: string; notify: boolean };
const empty: Form = { kind: 'lecture', titleAr: '', titleFr: '', descriptionAr: '', speaker: '', location: '', onlineUrl: '', startsAt: '', endsAt: '', capacity: '', registrationOpen: true, status: 'published', notify: true };

/** Problems named explicitly (same rules as the server). */
export function lectureProblems(f: Form): string[] {
  const p: string[] = [];
  if (f.titleAr.trim().length < 3) p.push('العنوان (3 أحرف على الأقل)');
  if (!f.startsAt) p.push('تاريخ ووقت البداية');
  if (!f.endsAt) p.push('تاريخ ووقت النهاية');
  else if (f.startsAt && fromInput(f.endsAt) <= fromInput(f.startsAt)) p.push('النهاية يجب أن تكون بعد البداية');
  if (f.onlineUrl.trim() && !f.onlineUrl.trim().startsWith('https://')) p.push('الرابط يجب أن يبدأ بـ https://');
  return p;
}

const csvCell = (v: unknown) => {
  const s = v == null ? '' : String(v);
  return `"${(/^[=+\-@]/.test(s) ? `'${s}` : s).replace(/"/g, '""')}"`;
};
export function registrationsCsv(items: Registration[]): string {
  const head = ['الاسم', 'اللقب', 'الهاتف', 'البريد', 'الجنس', 'سنة الميلاد', 'الولاية', 'الحالة العائلية', 'الأطفال', 'الوضعية', 'التفصيل', 'ملاحظة', 'الحالة', 'تاريخ التسجيل'];
  const rows = items.map((r) => [r.firstName, r.lastName, r.phone, r.email, GENDER[r.gender ?? ''] ?? '', r.birthYear, r.wilaya, MARITAL[r.maritalStatus ?? ''] ?? '', r.childrenCount, OCC[r.occupation ?? ''] ?? '', r.occupationDetail, r.note, REG[r.status] ?? r.status, dt(r.createdAt)]);
  return '﻿' + [head, ...rows].map((r) => r.map(csvCell).join(',')).join('\r\n');
}

export default function Lectures() {
  const { user } = useAuth();
  const { data, error, loading, reload } = useLoad(() => api.get<{ items: Lecture[] }>('/api/admin/lectures'));
  const [edit, setEdit] = useState<Form | null>(null);
  const [tried, setTried] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [people, setPeople] = useState<{ lecture: Lecture; items: Registration[] } | null>(null);

  const problems = edit ? lectureProblems(edit) : [];
  const save = async () => {
    if (!edit) return;
    setTried(true); setErr(null);
    if (problems.length) return;
    const body = {
      kind: edit.kind, titleAr: edit.titleAr.trim(), titleFr: edit.titleFr || undefined, descriptionAr: edit.descriptionAr || undefined,
      speaker: edit.speaker || undefined, location: edit.location || undefined, onlineUrl: edit.onlineUrl.trim() || undefined,
      startsAt: fromInput(edit.startsAt), endsAt: fromInput(edit.endsAt), capacity: edit.capacity ? Number(edit.capacity) : undefined,
      registrationOpen: edit.registrationOpen, status: edit.status, notify: edit.notify,
    };
    try {
      if (edit.id) await api.patch(`/api/admin/lectures/${edit.id}`, body); else await api.post('/api/admin/lectures', body);
      setEdit(null); reload();
    } catch (e) { setErr(errorText(e)); }
  };
  const remove = async () => {
    if (!edit?.id || !confirm('حذف هذه المحاضرة وكل تسجيلاتها نهائياً؟')) return;
    try { await api.del(`/api/admin/lectures/${edit.id}`); setEdit(null); reload(); } catch (e) { setErr(errorText(e)); }
  };
  const openPeople = async (l: Lecture) => {
    try { setPeople({ lecture: l, items: (await api.get<{ items: Registration[] }>(`/api/admin/lectures/${l.id}/registrations`)).items }); } catch (e) { alert(errorText(e)); }
  };
  const setReg = async (uid: string, status: string) => {
    if (!people) return;
    await api.patch(`/api/admin/lectures/${people.lecture.id}/registrations/${uid}`, { status });
    openPeople(people.lecture);
  };
  const toForm = (l: Lecture): Form => ({
    id: l.id, kind: l.kind, titleAr: l.titleAr, titleFr: l.titleFr ?? '', descriptionAr: l.descriptionAr ?? '', speaker: l.speaker ?? '', location: l.location ?? '',
    onlineUrl: l.onlineUrl ?? '', startsAt: toInput(l.startsAt), endsAt: toInput(l.endsAt), capacity: l.capacity?.toString() ?? '',
    registrationOpen: l.registrationOpenFlag ?? l.registrationOpen, status: l.status, notify: l.status !== 'published',
  });
  const setStart = (v: string) => {
    if (!edit) return;
    const end = edit.endsAt && fromInput(edit.endsAt) > fromInput(v) ? edit.endsAt : toInput(fromInput(v) + 2 * 3600_000);
    setEdit({ ...edit, startsAt: v, endsAt: v ? end : edit.endsAt });
  };

  return (
    <Page title="المحاضرات والاجتماعات" actions={<button className="btn" onClick={() => { setTried(false); setErr(null); setEdit({ ...empty }); }}>+ محاضرة / اجتماع</button>}>
      <State loading={loading && !data} error={error} empty={data?.items.length === 0} onRetry={reload} />
      {data && data.items.length > 0 && <div className="table-wrap"><table>
        <thead><tr><th>العنوان</th><th>النوع</th><th>الموعد</th><th>المحاضِر</th><th>الحالة</th><th>المسجلون</th><th /></tr></thead>
        <tbody>{data.items.map((l) => <tr key={l.id}>
          <td>{l.titleAr}</td><td>{KIND[l.kind]}</td><td>{dt(l.startsAt)}</td><td>{l.speaker ?? '—'}</td>
          <td><Badge tone={l.status === 'published' ? 'ok' : l.status === 'cancelled' ? 'bad' : 'neutral'}>{STATUS[l.status]}</Badge></td>
          <td>{l.registeredCount}{l.capacity ? ` / ${l.capacity}` : ''}</td>
          <td className="row-actions"><button className="btn ghost" onClick={() => { setTried(false); setErr(null); setEdit(toForm(l)); }}>تعديل</button><button className="btn ghost" onClick={() => openPeople(l)}>المسجلون</button></td>
        </tr>)}</tbody></table></div>}

      {edit && <Modal title={edit.id ? 'تعديل' : 'محاضرة / اجتماع جديد'} onClose={() => setEdit(null)}>
        <div className="form">
          <div className="row">
            <Field label="النوع"><select value={edit.kind} onChange={(e) => setEdit({ ...edit, kind: e.target.value })}>{Object.entries(KIND).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></Field>
            <Field label="الحالة"><select value={edit.status} onChange={(e) => setEdit({ ...edit, status: e.target.value })}>{Object.entries(STATUS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></Field>
          </div>
          <Field label="العنوان (عربية) *"><input value={edit.titleAr} aria-invalid={tried && edit.titleAr.trim().length < 3} onChange={(e) => setEdit({ ...edit, titleAr: e.target.value })} /></Field>
          <Field label="Titre (français)"><input dir="ltr" value={edit.titleFr} onChange={(e) => setEdit({ ...edit, titleFr: e.target.value })} /></Field>
          <Field label="المحاضِر / المؤطِّر"><input value={edit.speaker} onChange={(e) => setEdit({ ...edit, speaker: e.target.value })} /></Field>
          <Field label="الوصف"><textarea rows={4} value={edit.descriptionAr} onChange={(e) => setEdit({ ...edit, descriptionAr: e.target.value })} /></Field>
          <div className="row">
            <Field label="البداية *"><input type="datetime-local" value={edit.startsAt} aria-invalid={tried && !edit.startsAt} onChange={(e) => setStart(e.target.value)} /></Field>
            <Field label="النهاية *"><input type="datetime-local" value={edit.endsAt} aria-invalid={tried && !edit.endsAt} onChange={(e) => setEdit({ ...edit, endsAt: e.target.value })} /></Field>
          </div>
          <div className="row">
            <Field label="المكان"><input value={edit.location} onChange={(e) => setEdit({ ...edit, location: e.target.value })} /></Field>
            <Field label="عدد المقاعد"><input type="number" min={1} value={edit.capacity} onChange={(e) => setEdit({ ...edit, capacity: e.target.value })} /></Field>
          </div>
          <Field label="رابط عن بُعد (Meet / Zoom)"><input dir="ltr" placeholder="https://" value={edit.onlineUrl} onChange={(e) => setEdit({ ...edit, onlineUrl: e.target.value })} /></Field>
          <label className="check"><input type="checkbox" checked={edit.registrationOpen} onChange={(e) => setEdit({ ...edit, registrationOpen: e.target.checked })} /> التسجيل مفتوح</label>
          <label className="check"><input type="checkbox" checked={edit.notify} onChange={(e) => setEdit({ ...edit, notify: e.target.checked })} /> إشعار الأعضاء عند النشر</label>
          {tried && problems.length > 0 && <div className="state error">يرجى تصحيح: {problems.join('، ')}</div>}
          {err && <div className="state error">{err}</div>}
          <button className="btn" onClick={save}>حفظ</button>
          {edit.id && atLeast(user?.role, 'ADMIN') && <button className="btn ghost danger" onClick={remove}>حذف</button>}
        </div>
      </Modal>}

      {people && <Modal title={`المسجلون — ${people.lecture.titleAr} (${people.items.length})`} onClose={() => setPeople(null)} wide>
        {people.items.length === 0 ? <State empty /> : <>
          <div className="actions"><button className="btn ghost" onClick={() => saveText(`تسجيلات - ${people.lecture.titleAr}.csv`, registrationsCsv(people.items))}>⬇ تصدير Excel (CSV)</button></div>
          <div className="table-wrap"><table>
            <thead><tr><th>الاسم واللقب</th><th>الهاتف</th><th>الجنس</th><th>الميلاد</th><th>الولاية</th><th>العائلة</th><th>الوضعية</th><th>ملاحظة</th><th>الحالة</th><th /></tr></thead>
            <tbody>{people.items.map((r) => <tr key={r.userId}>
              <td>{r.firstName} {r.lastName}</td><td><bdi dir="ltr">{r.phone}</bdi></td><td>{GENDER[r.gender ?? ''] ?? '—'}</td><td>{r.birthYear ?? '—'}</td><td>{r.wilaya ?? '—'}</td>
              <td>{MARITAL[r.maritalStatus ?? ''] ?? '—'}{r.childrenCount != null ? ` · ${r.childrenCount} أطفال` : ''}</td>
              <td>{OCC[r.occupation ?? ''] ?? '—'}{r.occupationDetail ? ` (${r.occupationDetail})` : ''}</td>
              <td>{r.note ?? ''}</td><td><Badge tone={r.status === 'attended' ? 'ok' : r.status === 'cancelled' ? 'bad' : 'neutral'}>{REG[r.status]}</Badge></td>
              <td className="row-actions"><button className="btn ghost" onClick={() => setReg(r.userId, 'attended')}>حضر</button><button className="btn ghost" onClick={() => setReg(r.userId, 'cancelled')}>إلغاء</button></td>
            </tr>)}</tbody>
          </table></div>
        </>}
      </Modal>}
    </Page>
  );
}
