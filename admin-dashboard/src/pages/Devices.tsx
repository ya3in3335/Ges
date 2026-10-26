import { useState } from 'react';
import { useAuth } from '../App';
import { api, atLeast, errorText, type Role } from '../lib/api';
import { dt, ROLE_LABELS } from '../lib/format';
import { Badge, Field, Modal, Page, State, useLoad } from '../lib/ui';

interface Device { id: string; device_name: string | null; created_at: number; last_used_at: number | null; user_id: string; full_name: string; role: Role; current?: boolean; canRevoke?: boolean }

/** Supervisor devices (Android and Windows apps): one-time activation codes and remote disconnection. */
export default function Devices() {
  const { user } = useAuth();
  const { data, error, loading, reload } = useLoad(() => api.get<{ items: Device[] }>('/api/admin/devices'));
  const [form, setForm] = useState<{ name: string; role: Role } | null>(null);
  const [issued, setIssued] = useState<{ code: string; name: string; role: Role; expiresAt: number } | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const roles: Role[] = (['EDITOR', 'ADMIN', 'SUPER_ADMIN'] as Role[]).filter((r) => r !== 'SUPER_ADMIN' || user?.role === 'SUPER_ADMIN');

  const create = async () => {
    if (!form) return;
    setErr(null);
    try {
      const r = await api.post<{ code: string; name: string; role: Role; expiresAt: number }>('/api/admin/activation-codes', { name: form.name.trim(), role: form.role });
      setForm(null); setIssued(r);
    } catch (e) { setErr(errorText(e)); }
  };
  const revoke = async (d: Device) => {
    if (!confirm(`فصل جهاز «${d.device_name ?? '—'}» الخاص بـ ${d.full_name}؟`)) return;
    try { await api.del(`/api/admin/devices/${d.id}`); reload(); } catch (e) { alert(errorText(e)); }
  };

  return (
    <Page title="الأجهزة المفعّلة" actions={atLeast(user?.role, 'ADMIN') && <button className="btn" onClick={() => setForm({ name: '', role: 'EDITOR' })}>+ رمز تفعيل جديد</button>}>
      <p className="muted">كل جهاز (هاتف أو حاسوب) فُعِّل لتطبيق الإدارة. الفصل يوقف الجهاز فوراً ويُبلَّغ به مسؤولو البوت. يمكنك فصل جهازك أو أجهزة من هم أقل منك صفةً فقط.</p>
      <State loading={loading && !data} error={error} empty={data?.items.length === 0} onRetry={reload} />
      {data && data.items.length > 0 && <div className="table-wrap"><table>
        <thead><tr><th>الشخص</th><th>الصفة</th><th>الجهاز</th><th>فُعِّل في</th><th>آخر استعمال</th><th /></tr></thead>
        <tbody>{data.items.map((d) => <tr key={d.id}>
          <td>{d.full_name}</td><td><Badge tone={d.role === 'SUPER_ADMIN' ? 'warn' : 'neutral'}>{ROLE_LABELS[d.role]}</Badge></td>
          <td dir="auto">{d.device_name ?? '—'} {d.current && <Badge tone="ok">هذا الجهاز</Badge>}</td><td>{dt(d.created_at)}</td><td>{dt(d.last_used_at)}</td>
          <td className="row-actions">{d.canRevoke !== false && <button className="btn ghost danger" onClick={() => revoke(d)}>فصل</button>}</td>
        </tr>)}</tbody></table></div>}

      {form && <Modal title="رمز تفعيل لمشرف جديد" onClose={() => setForm(null)}>
        <div className="form">
          <Field label="اسم الشخص"><input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} autoFocus /></Field>
          <Field label="الصفة"><select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value as Role })}>
            {roles.map((r) => <option key={r} value={r}>{ROLE_LABELS[r]}</option>)}
          </select></Field>
          {err && <div className="state error">{err}</div>}
          <button className="btn" disabled={form.name.trim().length < 2} onClick={create}>إنشاء الرمز</button>
        </div>
      </Modal>}

      {issued && <Modal title="رمز التفعيل" onClose={() => { setIssued(null); reload(); }}>
        <div className="form">
          <p>أعطِ هذا الرمز لـ <b>{issued.name}</b> ({ROLE_LABELS[issued.role]}). يُستعمل مرة واحدة، وصالح حتى {dt(issued.expiresAt)}.</p>
          <div className="code">{issued.code}</div>
          <small className="muted">يُدخَل في تطبيق الإدارة (أندرويد أو ويندوز) عبر «لدي رمز تفعيل». لن يظهر هذا الرمز مرة أخرى.</small>
        </div>
      </Modal>}
    </Page>
  );
}
