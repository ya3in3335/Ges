import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { errorText } from './api';

/** Loads data and exposes loading / error / reload. */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const reload = useCallback(async () => {
    setLoading(true); setError(null);
    try { setData(await fn()); } catch (e) { setError(errorText(e)); } finally { setLoading(false); }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  useEffect(() => { void reload(); }, [reload]);
  return { data, error, loading, reload, setData };
}

export function Page({ title, actions, children }: { title: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="page">
      <header className="page-head"><h1>{title}</h1><div className="actions">{actions}</div></header>
      {children}
    </section>
  );
}

export function State({ loading, error, empty, onRetry }: { loading?: boolean; error?: string | null; empty?: boolean; onRetry?: () => void }) {
  if (loading) return <div className="state"><div className="spinner" aria-label="جارٍ التحميل" /></div>;
  if (error) return <div className="state error" role="alert">{error} {onRetry && <button className="btn ghost" onClick={onRetry}>إعادة المحاولة</button>}</div>;
  if (empty) return <div className="state">لا توجد بيانات</div>;
  return null;
}

export function Pager({ page, hasMore, onPage }: { page: number; hasMore: boolean; onPage: (p: number) => void }) {
  return (
    <div className="pager">
      <button className="btn ghost" disabled={page <= 1} onClick={() => onPage(page - 1)}>السابق</button>
      <span>صفحة {page}</span>
      <button className="btn ghost" disabled={!hasMore} onClick={() => onPage(page + 1)}>التالي</button>
    </div>
  );
}

export function Modal({ title, onClose, children, wide }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className={wide ? 'modal wide' : 'modal'} role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <header><h2>{title}</h2><button className="btn ghost" onClick={onClose} aria-label="إغلاق">✕</button></header>
        {children}
      </div>
    </div>
  );
}

export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) {
  return <label className="field"><span>{label}</span>{children}{hint && <small>{hint}</small>}</label>;
}

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'ok' | 'warn' | 'bad' }) {
  return <span className={`badge ${tone}`}>{children}</span>;
}
