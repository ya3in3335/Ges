import { useState } from 'react';
import { api, errorText, mediaUrl } from '../lib/api';
import { Badge, Field, Modal, Page, Pager, State, useLoad } from '../lib/ui';

interface M { id: string; kind: string; titleAr: string | null; category: string; url: string; thumbnailUrl: string | null; featured: boolean; status: string; sizeBytes: number | null }

export default function Media() {
  const [page, setPage] = useState(1);
  const { data, error, loading, reload } = useLoad(() => api.get<{ items: M[]; hasMore: boolean; r2Enabled: boolean }>(`/api/admin/media?page=${page}`), [page]);
  const [mode, setMode] = useState<'link' | 'upload' | null>(null);
  const [form, setForm] = useState({ kind: 'photo', url: '', thumbnailUrl: '', titleAr: '', category: 'general', featured: false });
  const [file, setFile] = useState<File | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    setErr(null); setBusy(true);
    try {
      if (mode === 'link') {
        await api.post('/api/admin/media', { ...form, thumbnailUrl: form.thumbnailUrl || null, titleAr: form.titleAr || null });
      } else if (file) {
        const fd = new FormData();
        fd.append('file', file);
        fd.append('titleAr', form.titleAr);
        fd.append('category', form.category);
        fd.append('featured', String(form.featured));
        await api.post('/api/admin/media/upload', fd);
      }
      setMode(null); reload();
    } catch (e) { setErr(errorText(e)); } finally { setBusy(false); }
  };

  return (
    <Page title="الصور والفيديوهات" actions={<>
      <button className="btn" onClick={() => setMode('link')}>+ رابط خارجي</button>
      <button className="btn ghost" disabled={!data?.r2Enabled} title={data?.r2Enabled ? '' : 'R2 غير مفعّل'} onClick={() => setMode('upload')}>رفع ملف</button>
    </>}>
      {data && !data.r2Enabled && <p className="note">رفع الملفات معطّل لأن Cloudflare R2 غير مفعّل. يُنصح بنشر الفيديوهات على YouTube وإضافة روابطها لتقليل التكلفة.</p>}
      <State loading={loading && !data} error={error} empty={data?.items.length === 0} onRetry={reload} />
      {data && <div className="grid">{data.items.map((m) => <figure key={m.id} className={m.status === 'hidden' ? 'muted' : ''}>
        {m.kind === 'photo' || m.thumbnailUrl ? <img src={mediaUrl(m.thumbnailUrl ?? m.url)} alt={m.titleAr ?? ''} loading="lazy" /> : <div className="video-ph">▶</div>}
        <figcaption>
          <span>{m.titleAr ?? '—'}</span> <Badge>{m.category}</Badge> {m.featured && <Badge tone="warn">مميز</Badge>}
          <div className="row-actions">
            <button className="btn ghost" onClick={async () => { await api.patch(`/api/admin/media/${m.id}`, { status: m.status === 'hidden' ? 'published' : 'hidden' }); reload(); }}>{m.status === 'hidden' ? 'إظهار' : 'إخفاء'}</button>
            <button className="btn ghost danger" onClick={async () => { if (confirm('حذف نهائي؟')) { await api.del(`/api/admin/media/${m.id}`); reload(); } }}>حذف</button>
          </div>
        </figcaption>
      </figure>)}</div>}
      {data && <Pager page={page} hasMore={data.hasMore} onPage={setPage} />}

      {mode && <Modal title={mode === 'link' ? 'إضافة رابط' : 'رفع ملف'} onClose={() => setMode(null)}>
        <div className="form">
          {mode === 'link' ? <>
            <Field label="النوع"><select value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value })}><option value="photo">صورة</option><option value="video">فيديو</option></select></Field>
            <Field label="الرابط (https)"><input dir="ltr" value={form.url} onChange={(e) => setForm({ ...form, url: e.target.value })} /></Field>
            <Field label="رابط الصورة المصغرة (اختياري)"><input dir="ltr" value={form.thumbnailUrl} onChange={(e) => setForm({ ...form, thumbnailUrl: e.target.value })} /></Field>
          </> : <Field label="الملف" hint="JPEG / PNG / WebP حتى 8 ميغابايت، MP4 حتى 60 ميغابايت"><input type="file" accept="image/jpeg,image/png,image/webp,video/mp4" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></Field>}
          <Field label="العنوان"><input value={form.titleAr} onChange={(e) => setForm({ ...form, titleAr: e.target.value })} /></Field>
          <Field label="التصنيف"><input value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })} /></Field>
          <label className="check"><input type="checkbox" checked={form.featured} onChange={(e) => setForm({ ...form, featured: e.target.checked })} /> مميز</label>
          {err && <div className="state error">{err}</div>}
          <button className="btn" disabled={busy} onClick={submit}>{busy ? '…' : 'حفظ'}</button>
        </div>
      </Modal>}
    </Page>
  );
}
