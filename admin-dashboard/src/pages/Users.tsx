import { useState } from 'react';
import { api, errorText, type Role } from '../lib/api';
import { dt, ROLE_LABELS } from '../lib/format';
import { Badge, Page, State, useLoad } from '../lib/ui';
import { useAuth } from '../App';

interface Row { id: string; full_name: string; email: string; phone_e164: string; role: Role; status: string; phone_verified_at: number | null; created_at: number; telegram_linked: number }

export default function Users() {
  const { user: me } = useAuth();
  const [q, setQ] = useState('');
  const [query, setQuery] = useState('');
  const [role, setRole] = useState('');
  const [verified, setVerified] = useState('');
  const [page, setPage] = useState(1);
  const [msg, setMsg] = useState<string | null>(null);
  const params = new URLSearchParams({ page: String(page), pageSize: '25', ...(query && { q: query }), ...(role && { role }), ...(verified && { verified }) });
  const { data, error, loading, reload } = useLoad(() => api.get<{ items: Row[]; total: number }>(`/api/admin/users?${params}`), [params.toString()]);

  const act = async (fn: () => Promise<unknown>) => {
    setMsg(null);
    try { await fn(); await reload(); } catch (e) { setMsg(errorText(e)); }
  };
  const pages = Math.max(1, Math.ceil((data?.total ?? 0) / 25));

  return (
    <Page title="المستخدمون">
      <form className="filters" onSubmit={(e) => { e.preventDefault(); setPage(1); setQuery(q); }}>
        <input placeholder="بحث بالاسم أو البريد أو الهاتف" value={q} onChange={(e) => setQ(e.target.value)} />
        <select value={role} onChange={(e) => { setPage(1); setRole(e.target.value); }}>
          <option value="">كل الصفات</option>
          {Object.entries(ROLE_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        <select value={verified} onChange={(e) => { setPage(1); setVerified(e.target.value); }}>
          <option value="">الكل</option><option value="1">مؤكد</option><option value="0">غير مؤكد</option>
        </select>
        <button className="btn">بحث</button>
      </form>
      {msg && <div className="state error">{msg}</div>}
      <State loading={loading && !data} error={error} empty={data?.items.length === 0} onRetry={reload} />
      {data && data.items.length > 0 && (
        <div className="table-wrap"><table>
          <thead><tr><th>الاسم</th><th>البريد</th><th>الهاتف</th><th>التحقق</th><th>الصفة</th><th>الحالة</th><th>التسجيل</th><th /></tr></thead>
          <tbody>{data.items.map((u) => (
            <tr key={u.id}>
              <td>{u.full_name}</td>
              <td dir="ltr">{u.email}</td>
              <td dir="ltr">{u.phone_e164}</td>
              <td>{u.phone_verified_at ? <Badge tone="ok">مؤكد{u.telegram_linked ? ' · تيليغرام' : ''}</Badge> : <Badge>غير مؤكد</Badge>}</td>
              <td>
                <select value={u.role} disabled={u.id === me?.id} onChange={(e) => act(() => api.patch(`/api/admin/users/${u.id}`, { role: e.target.value }))}>
                  {Object.entries(ROLE_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select>
              </td>
              <td>{u.status === 'active' ? <Badge tone="ok">نشط</Badge> : <Badge tone="bad">موقوف</Badge>}</td>
              <td>{dt(u.created_at)}</td>
              <td className="row-actions">
                {u.id !== me?.id && (u.status === 'active'
                  ? <button className="btn ghost" onClick={() => confirm('إيقاف هذا الحساب؟') && act(() => api.patch(`/api/admin/users/${u.id}`, { status: 'suspended' }))}>إيقاف</button>
                  : <button className="btn ghost" onClick={() => act(() => api.patch(`/api/admin/users/${u.id}`, { status: 'active' }))}>تفعيل</button>)}
                {u.phone_verified_at && <button className="btn ghost" onClick={() => confirm('إلغاء تأكيد الرقم وفك ربط تيليغرام؟') && act(() => api.post(`/api/admin/users/${u.id}/reset-verification`))}>إلغاء التحقق</button>}
              </td>
            </tr>))}
          </tbody>
        </table></div>
      )}
      {data && <div className="pager"><button className="btn ghost" disabled={page <= 1} onClick={() => setPage(page - 1)}>السابق</button><span>{page} / {pages} — {data.total} مستخدم</span><button className="btn ghost" disabled={page >= pages} onClick={() => setPage(page + 1)}>التالي</button></div>}
    </Page>
  );
}
