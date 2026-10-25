import { useState } from 'react';
import { api, errorText } from '../lib/api';
import { dt, fromInput, toInput } from '../lib/format';
import { Badge, Field, Modal, Page, State, useLoad } from '../lib/ui';

interface Poll { id: string; titleAr: string; titleFr: string | null; descriptionAr: string | null; startsAt: number; endsAt: number; eligibility: string; resultVisibility: string; anonymous: boolean; status: string; state: string; votes: number; members: number }
interface Results { options: { id: string; labelAr: string; votes: number }[]; totalVotes: number; eligibleVoters: number }
const VIS: Record<string, string> = { live: 'مباشرة', after_close: 'بعد الإغلاق', admins: 'للإدارة فقط' };
const STATE: Record<string, string> = { upcoming: 'قادم', active: 'جارٍ', closed: 'منتهٍ' };

type Form = { id?: string; titleAr: string; titleFr: string; descriptionAr: string; startsAt: string; endsAt: string; eligibility: string; resultVisibility: string; anonymous: boolean; status: string; options: string[]; locked: boolean };
const empty: Form = { titleAr: '', titleFr: '', descriptionAr: '', startsAt: '', endsAt: '', eligibility: 'verified', resultVisibility: 'after_close', anonymous: true, status: 'draft', options: ['', ''], locked: false };

export default function Polls() {
  const { data, error, loading, reload } = useLoad(() => api.get<{ items: Poll[] }>('/api/admin/polls'));
  const [edit, setEdit] = useState<Form | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [results, setResults] = useState<{ poll: Poll; r: Results } | null>(null);
  const [members, setMembers] = useState<{ poll: Poll; text: string; out?: string } | null>(null);

  const save = async () => {
    if (!edit) return;
    setErr(null);
    const body: Record<string, unknown> = {
      titleAr: edit.titleAr, titleFr: edit.titleFr || null, descriptionAr: edit.descriptionAr || null,
      startsAt: fromInput(edit.startsAt), endsAt: fromInput(edit.endsAt), status: edit.status,
    };
    if (!edit.locked) Object.assign(body, {
      eligibility: edit.eligibility, resultVisibility: edit.resultVisibility, anonymous: edit.anonymous,
      options: edit.options.filter((o) => o.trim()).map((labelAr) => ({ labelAr })),
    });
    if (edit.locked) delete body.startsAt;
    try {
      if (edit.id) await api.patch(`/api/admin/polls/${edit.id}`, body); else await api.post('/api/admin/polls', body);
      setEdit(null); reload();
    } catch (e) { setErr(errorText(e)); }
  };

  const openEdit = async (p: Poll) => {
    const d = await api.get<{ options: { labelAr: string }[] }>(`/api/polls/${p.id}`);
    setEdit({ id: p.id, titleAr: p.titleAr, titleFr: p.titleFr ?? '', descriptionAr: p.descriptionAr ?? '', startsAt: toInput(p.startsAt), endsAt: toInput(p.endsAt),
      eligibility: p.eligibility, resultVisibility: p.resultVisibility, anonymous: p.anonymous, status: p.status, options: d.options.map((o) => o.labelAr), locked: p.votes > 0 });
  };

  const addMembers = async () => {
    if (!members) return;
    const identifiers = members.text.split(/[\n,;]+/).map((s) => s.trim()).filter(Boolean);
    try {
      const r = await api.post<{ added: number; notFound: string[] }>(`/api/admin/polls/${members.poll.id}/members`, { identifiers });
      setMembers({ ...members, text: '', out: `أُضيف ${r.added}. غير موجود: ${r.notFound.join('، ') || '—'}` });
      reload();
    } catch (e) { setMembers({ ...members, out: errorText(e) }); }
  };

  return (
    <Page title="التصويت" actions={<button className="btn" onClick={() => setEdit({ ...empty, options: ['', ''] })}>+ تصويت جديد</button>}>
      <p className="note">يتطلب التصويت حساباً برقم مؤكد عبر تيليغرام. للانتخابات الرسمية استخدم «قائمة الأعضاء المعتمدين»، لأن الحساب المؤكد لا يعني بالضرورة شخصاً فريداً.</p>
      <State loading={loading && !data} error={error} empty={data?.items.length === 0} onRetry={reload} />
      {data && data.items.length > 0 && <div className="table-wrap"><table>
        <thead><tr><th>العنوان</th><th>الفترة</th><th>المرحلة</th><th>الأهلية</th><th>النتائج</th><th>الأصوات</th><th /></tr></thead>
        <tbody>{data.items.map((p) => <tr key={p.id}>
          <td>{p.titleAr} {p.status !== 'published' && <Badge>{p.status === 'draft' ? 'مسودة' : 'ملغى'}</Badge>} {p.anonymous && <Badge>سري</Badge>}</td>
          <td>{dt(p.startsAt)} → {dt(p.endsAt)}</td><td>{STATE[p.state]}</td>
          <td>{p.eligibility === 'members' ? `أعضاء (${p.members})` : 'أرقام مؤكدة'}</td><td>{VIS[p.resultVisibility]}</td><td>{p.votes}</td>
          <td className="row-actions">
            <button className="btn ghost" onClick={() => openEdit(p)}>تعديل</button>
            <button className="btn ghost" onClick={async () => setResults({ poll: p, r: await api.get<Results>(`/api/admin/polls/${p.id}/results`) })}>النتائج</button>
            {p.eligibility === 'members' && <button className="btn ghost" onClick={() => setMembers({ poll: p, text: '' })}>الأعضاء</button>}
          </td></tr>)}</tbody></table></div>}

      {edit && <Modal title={edit.id ? 'تعديل تصويت' : 'تصويت جديد'} onClose={() => setEdit(null)}>
        <div className="form">
          {edit.locked && <div className="state">بدأ الاقتراع: لا يمكن تغيير الخيارات أو قواعد الأهلية أو السرية أو تمديد الموعد.</div>}
          <Field label="العنوان *"><input value={edit.titleAr} onChange={(e) => setEdit({ ...edit, titleAr: e.target.value })} /></Field>
          <Field label="Titre (français)"><input dir="ltr" value={edit.titleFr} onChange={(e) => setEdit({ ...edit, titleFr: e.target.value })} /></Field>
          <Field label="الوصف"><textarea rows={3} value={edit.descriptionAr} onChange={(e) => setEdit({ ...edit, descriptionAr: e.target.value })} /></Field>
          <div className="row">
            <Field label="البداية *"><input type="datetime-local" disabled={edit.locked} value={edit.startsAt} onChange={(e) => setEdit({ ...edit, startsAt: e.target.value })} /></Field>
            <Field label="النهاية *"><input type="datetime-local" value={edit.endsAt} onChange={(e) => setEdit({ ...edit, endsAt: e.target.value })} /></Field>
          </div>
          <div className="row">
            <Field label="الأهلية"><select disabled={edit.locked} value={edit.eligibility} onChange={(e) => setEdit({ ...edit, eligibility: e.target.value })}><option value="verified">كل الأرقام المؤكدة</option><option value="members">قائمة أعضاء معتمدة</option></select></Field>
            <Field label="ظهور النتائج"><select disabled={edit.locked} value={edit.resultVisibility} onChange={(e) => setEdit({ ...edit, resultVisibility: e.target.value })}>{Object.entries(VIS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></Field>
            <Field label="الحالة"><select value={edit.status} onChange={(e) => setEdit({ ...edit, status: e.target.value })}><option value="draft">مسودة</option><option value="published">منشور</option><option value="cancelled">ملغى</option></select></Field>
          </div>
          <label className="check"><input type="checkbox" disabled={edit.locked} checked={edit.anonymous} onChange={(e) => setEdit({ ...edit, anonymous: e.target.checked })} /> تصويت سري (لا تُربط الأصوات بالحسابات)</label>
          <fieldset disabled={edit.locked}><legend>الخيارات / المرشحون</legend>
            {edit.options.map((o, i) => <div className="row" key={i}>
              <input value={o} placeholder={`الخيار ${i + 1}`} onChange={(e) => setEdit({ ...edit, options: edit.options.map((x, j) => (j === i ? e.target.value : x)) })} />
              {edit.options.length > 2 && <button className="btn ghost" onClick={() => setEdit({ ...edit, options: edit.options.filter((_, j) => j !== i) })}>✕</button>}
            </div>)}
            <button className="btn ghost" onClick={() => setEdit({ ...edit, options: [...edit.options, ''] })}>+ خيار</button>
          </fieldset>
          {err && <div className="state error">{err}</div>}
          <button className="btn" onClick={save}>حفظ</button>
        </div>
      </Modal>}

      {results && <Modal title={`النتائج — ${results.poll.titleAr}`} onClose={() => setResults(null)}>
        <p>الأصوات: {results.r.totalVotes} من {results.r.eligibleVoters} ناخب مؤهل</p>
        {results.r.options.map((o) => {
          const pct = results.r.totalVotes ? Math.round((o.votes / results.r.totalVotes) * 100) : 0;
          return <div key={o.id} className="result"><span>{o.labelAr}</span><div className="meter"><i style={{ width: `${pct}%` }} /></div><b>{o.votes} ({pct}%)</b></div>;
        })}
      </Modal>}

      {members && <Modal title={`الأعضاء المعتمدون — ${members.poll.titleAr}`} onClose={() => setMembers(null)}>
        <div className="form">
          <Field label="البريد الإلكتروني أو رقم الهاتف لكل عضو (سطر لكل عضو)"><textarea dir="ltr" rows={8} value={members.text} onChange={(e) => setMembers({ ...members, text: e.target.value })} /></Field>
          {members.out && <div className="state">{members.out}</div>}
          <button className="btn" onClick={addMembers}>إضافة</button>
        </div>
      </Modal>}
    </Page>
  );
}
