import { useEffect, useState } from 'react';
import { api, atLeast, errorText, type AdminUser } from '../lib/api';
import { desktop } from '../lib/desktop';

const PENDING = 'activation_request';

/**
 * Windows app activation (no password): either the association's approver accepts the request in
 * the Telegram bot, or a supervisor gives a one-time 8-digit code from the Devices page.
 */
export default function Activate({ onActivated }: { onActivated: (u: AdminUser) => void }) {
  const saved = (() => { try { return JSON.parse(desktop?.store.get(PENDING) ?? 'null') as { id: string; secret: string } | null; } catch { return null; } })();
  const [mode, setMode] = useState<'request' | 'code'>('request');
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [pending, setPending] = useState(saved);
  const [status, setStatus] = useState<string | null>(saved ? 'pending' : null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const deviceName = desktop?.deviceName ?? 'Windows';

  const finish = (u?: AdminUser) => {
    desktop?.store.remove(PENDING);
    if (u && atLeast(u.role, 'EDITOR')) onActivated(u);
    else { api.clear(); setErr('هذا الحساب ليست له صلاحية الإدارة'); }
  };

  useEffect(() => {
    if (!pending) return;
    let stop = false;
    const tick = async () => {
      try {
        const r = await api.pollActivation(pending.id, pending.secret);
        if (stop) return;
        setStatus(r.status);
        if (r.status === 'approved') finish(r.user);
        else if (r.status !== 'pending') { desktop?.store.remove(PENDING); setPending(null); }
      } catch { /* offline: keep waiting */ }
    };
    void tick();
    const t = setInterval(tick, 4000);
    return () => { stop = true; clearInterval(t); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pending]);

  const request = async () => {
    setErr(null); setBusy(true);
    try {
      const r = await api.requestActivation(name.trim(), deviceName);
      const p = { id: r.requestId, secret: r.secret };
      desktop?.store.set(PENDING, JSON.stringify(p));
      setStatus('pending'); setPending(p);
    } catch (e) { setErr(errorText(e)); } finally { setBusy(false); }
  };

  const activate = async () => {
    setErr(null); setBusy(true);
    try { finish(await api.activate(code, deviceName)); } catch (e) { setErr(errorText(e)); } finally { setBusy(false); }
  };

  const cancel = () => { desktop?.store.remove(PENDING); setPending(null); setStatus(null); };

  return (
    <div className="login">
      <div className="card">
        <img src="/logo.webp" alt="شعار الجمعية" className="login-logo" />
        <h1>تفعيل تطبيق الإدارة</h1>
        <p className="muted">هذا الحاسوب: <b dir="ltr">{deviceName}</b></p>

        {pending ? (
          <div className="form">
            <div className="spinner" aria-label="بانتظار الموافقة" />
            <p>تم إرسال طلب التفعيل إلى مسؤول الجمعية عبر بوت تيليغرام.<br />سيُفعَّل التطبيق تلقائياً فور قبول الطلب.</p>
            <button className="btn ghost" onClick={cancel}>إلغاء الطلب</button>
          </div>
        ) : (
          <>
            <div className="tabs">
              <button className={mode === 'request' ? 'tab active' : 'tab'} onClick={() => { setMode('request'); setErr(null); }}>طلب التفعيل</button>
              <button className={mode === 'code' ? 'tab active' : 'tab'} onClick={() => { setMode('code'); setErr(null); }}>لدي رمز تفعيل</button>
            </div>
            {status === 'rejected' && <div className="state error">رُفض طلب التفعيل.</div>}
            {status === 'expired' && <div className="state error">انتهت صلاحية الطلب، أعد المحاولة.</div>}
            {mode === 'request' ? (
              <div className="form">
                <label className="field"><span>اسمك الكامل</span>
                  <input value={name} onChange={(e) => setName(e.target.value)} placeholder="مثال: محمد بن علي" autoFocus />
                </label>
                <button className="btn" disabled={busy || name.trim().length < 2} onClick={request}>طلب التفعيل</button>
                <small className="muted">يصل الطلب إلى مسؤول الجمعية ليحدد صلاحيتك (محرر، مشرف، مشرف عام).</small>
              </div>
            ) : (
              <div className="form">
                <label className="field"><span>رمز التفعيل (8 أرقام)</span>
                  <input dir="ltr" inputMode="numeric" maxLength={8} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 8))} autoFocus />
                </label>
                <button className="btn" disabled={busy || code.length !== 8} onClick={activate}>تفعيل</button>
                <small className="muted">يُنشئ الرمزَ مشرفٌ أو المشرف العام من قسم «الأجهزة».</small>
              </div>
            )}
          </>
        )}
        {err && <div className="state error" role="alert">{err}</div>}
      </div>
    </div>
  );
}
