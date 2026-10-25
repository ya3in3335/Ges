import { useEffect, useState } from 'react';
import { api, errorText } from '../lib/api';
import { Field, Page, State, useLoad } from '../lib/ui';

const FIELDS: [string, string, boolean?][] = [
  ['ccpAccount', 'رقم الحساب البريدي الجاري (CCP)'], ['ccpKey', 'مفتاح CCP'], ['ccpHolder', 'صاحب الحساب البريدي'],
  ['bankName', 'اسم البنك'], ['bankAccount', 'رقم الحساب البنكي (RIB)'], ['bankHolder', 'صاحب الحساب البنكي'],
  ['contactPhone', 'هاتف التبرعات'], ['contactEmail', 'بريد التبرعات'],
  ['instructionsAr', 'تعليمات التبرع (عربية)', true], ['instructionsFr', 'Instructions (français)', true],
];

export default function Donations() {
  const { data, error, loading, reload } = useLoad(() => api.get<{ donation: Record<string, string | null> }>('/api/donations'));
  const [form, setForm] = useState<Record<string, string>>({});
  const [msg, setMsg] = useState<string | null>(null);
  useEffect(() => { if (data) setForm(Object.fromEntries(FIELDS.map(([k]) => [k, data.donation[k] ?? '']))); }, [data]);

  const save = async () => {
    setMsg(null);
    try { await api.put('/api/admin/donations', Object.fromEntries(Object.entries(form).map(([k, v]) => [k, v.trim() || null]))); setMsg('تم الحفظ'); } catch (e) { setMsg(errorText(e)); }
  };

  return (
    <Page title="معلومات التبرع">
      <p className="note">تُعرض هذه المعلومات كما هي في التطبيق. أدخل المعلومات الرسمية فقط، وراجعها جيداً قبل الحفظ. لا يتم أي دفع داخل التطبيق.</p>
      <State loading={loading && !data} error={error} onRetry={reload} />
      {data && <div className="form card">
        {FIELDS.map(([k, label, multi]) => <Field key={k} label={label}>
          {multi ? <textarea rows={4} dir={k.endsWith('Fr') ? 'ltr' : undefined} value={form[k] ?? ''} onChange={(e) => setForm({ ...form, [k]: e.target.value })} />
            : <input dir={/Account|Key|Phone|Email/.test(k) ? 'ltr' : undefined} value={form[k] ?? ''} onChange={(e) => setForm({ ...form, [k]: e.target.value })} />}
        </Field>)}
        {msg && <div className="state">{msg}</div>}
        <button className="btn" onClick={save}>حفظ</button>
      </div>}
    </Page>
  );
}
