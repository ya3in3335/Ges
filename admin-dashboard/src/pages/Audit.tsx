import { useState } from 'react';
import { api } from '../lib/api';
import { dt } from '../lib/format';
import { Page, Pager, State, useLoad } from '../lib/ui';

interface Row { id: number; actor_name?: string; actor_role?: string; action?: string; entity_type?: string; entity_id?: string; detail?: string; ip?: string; event?: string; user_id?: string; verification_id?: string; created_at: number }

export default function Audit() {
  const [source, setSource] = useState<'admin' | 'verification'>('admin');
  const [page, setPage] = useState(1);
  const { data, error, loading, reload } = useLoad(() => api.get<{ items: Row[]; hasMore: boolean }>(`/api/admin/audit?source=${source}&page=${page}&pageSize=50`), [source, page]);
  return (
    <Page title="سجل التدقيق">
      <div className="filters">
        <select value={source} onChange={(e) => { setPage(1); setSource(e.target.value as 'admin'); }}>
          <option value="admin">إجراءات الإدارة</option><option value="verification">التحقق عبر تيليغرام</option>
        </select>
      </div>
      <State loading={loading && !data} error={error} empty={data?.items.length === 0} onRetry={reload} />
      {data && data.items.length > 0 && <div className="table-wrap"><table>
        {source === 'admin'
          ? <><thead><tr><th>التاريخ</th><th>المنفّذ</th><th>الإجراء</th><th>العنصر</th><th>التفاصيل</th></tr></thead>
            <tbody>{data.items.map((r) => <tr key={r.id}><td>{dt(r.created_at)}</td><td>{r.actor_name ?? '—'} <small>{r.actor_role}</small></td><td>{r.action}</td><td>{r.entity_type} <small dir="ltr">{r.entity_id?.slice(0, 8)}</small></td><td><code dir="ltr">{r.detail}</code></td></tr>)}</tbody></>
          : <><thead><tr><th>التاريخ</th><th>الحدث</th><th>المستخدم</th><th>التفاصيل</th></tr></thead>
            <tbody>{data.items.map((r) => <tr key={r.id}><td>{dt(r.created_at)}</td><td>{r.event}</td><td dir="ltr">{r.user_id?.slice(0, 8) ?? '—'}</td><td><code dir="ltr">{r.detail}</code></td></tr>)}</tbody></>}
      </table></div>}
      {data && <Pager page={page} hasMore={data.hasMore} onPage={setPage} />}
    </Page>
  );
}
