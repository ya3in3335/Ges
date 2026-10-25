import { useState, type FormEvent } from 'react';
import { api, atLeast, errorText, type AdminUser } from '../lib/api';

export default function Login({ onLogin }: { onLogin: (u: AdminUser) => void }) {
  const [id, setId] = useState('');
  const [pw, setPw] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true); setErr(null);
    try {
      const u = await api.login(id, pw);
      if (!atLeast(u.role, 'EDITOR')) { await api.logout(); setErr('ليست لديك صلاحية الدخول إلى لوحة الإدارة'); return; }
      onLogin(u);
    } catch (e) { setErr(errorText(e)); } finally { setBusy(false); }
  };

  return (
    <div className="login">
      <form onSubmit={submit} className="card">
        <img src="/logo.webp" alt="شعار الجمعية" className="login-logo" />
        <h1>لوحة الإدارة</h1>
        <label className="field"><span>البريد الإلكتروني أو الهاتف</span><input dir="ltr" value={id} onChange={(e) => setId(e.target.value)} autoComplete="username" required /></label>
        <label className="field"><span>كلمة المرور</span><input dir="ltr" type="password" value={pw} onChange={(e) => setPw(e.target.value)} autoComplete="current-password" required /></label>
        {err && <div className="state error" role="alert">{err}</div>}
        <button className="btn" disabled={busy}>{busy ? '…' : 'دخول'}</button>
      </form>
    </div>
  );
}
