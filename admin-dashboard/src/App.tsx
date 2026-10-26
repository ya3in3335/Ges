import { createContext, useContext, useEffect, useState } from 'react';
import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { api, atLeast, type AdminUser, type Role } from './lib/api';
import { ROLE_LABELS } from './lib/format';
import Login from './pages/Login';
import Activate from './pages/Activate';
import Lectures from './pages/Lectures';
import Devices from './pages/Devices';
import { desktop } from './lib/desktop';
import Overview from './pages/Overview';
import Users from './pages/Users';
import TelegramStats from './pages/TelegramStats';
import News from './pages/News';
import Activities from './pages/Activities';
import Polls from './pages/Polls';
import Volunteers from './pages/Volunteers';
import Media from './pages/Media';
import Donations from './pages/Donations';
import Notifications from './pages/Notifications';
import Settings from './pages/Settings';
import Audit from './pages/Audit';

const AuthCtx = createContext<{ user: AdminUser | null }>({ user: null });
export const useAuth = () => useContext(AuthCtx);

// Menu entries are hidden by role for convenience; the server enforces every permission.
const NAV: { to: string; label: string; min: Role }[] = [
  { to: '/', label: 'نظرة عامة', min: 'EDITOR' },
  { to: '/users', label: 'المستخدمون', min: 'ADMIN' },
  { to: '/devices', label: 'الأجهزة المفعّلة', min: 'ADMIN' },
  { to: '/telegram', label: 'إحصاءات التحقق', min: 'ADMIN' },
  { to: '/news', label: 'الأخبار', min: 'EDITOR' },
  { to: '/lectures', label: 'المحاضرات والاجتماعات', min: 'EDITOR' },
  { to: '/activities', label: 'النشاطات', min: 'EDITOR' },
  { to: '/polls', label: 'التصويت', min: 'ADMIN' },
  { to: '/volunteers', label: 'المتطوعون', min: 'ADMIN' },
  { to: '/media', label: 'الوسائط', min: 'EDITOR' },
  { to: '/donations', label: 'التبرعات', min: 'ADMIN' },
  { to: '/notifications', label: 'الإشعارات', min: 'EDITOR' },
  { to: '/settings', label: 'الإعدادات', min: 'SUPER_ADMIN' },
  { to: '/audit', label: 'سجل التدقيق', min: 'ADMIN' },
];

export default function App() {
  const [user, setUser] = useState<AdminUser | null>(null);
  const [checking, setChecking] = useState(api.hasSession);
  const [menuOpen, setMenuOpen] = useState(false);

  useEffect(() => {
    api.onSessionEnd = () => setUser(null);
    if (!api.hasSession) return;
    api.get<{ user: AdminUser }>('/api/me')
      .then((r) => (atLeast(r.user.role, 'EDITOR') ? setUser(r.user) : api.clear()))
      .catch(() => api.clear())
      .finally(() => setChecking(false));
  }, []);

  if (checking) return <div className="state"><div className="spinner" /></div>;
  if (!user) return desktop ? <Activate onActivated={setUser} /> : <Login onLogin={setUser} />;

  const allowed = (min: Role) => atLeast(user.role, min);
  const guard = (min: Role, el: JSX.Element) => (allowed(min) ? el : <Navigate to="/" replace />);

  return (
    <AuthCtx.Provider value={{ user }}>
      <div className="shell">
        <aside className={menuOpen ? 'side open' : 'side'}>
          <div className="brand"><img src="/logo.webp" alt="شعار الجمعية" /><span>لوحة الإدارة</span></div>
          <nav onClick={() => setMenuOpen(false)}>
            {NAV.filter((n) => allowed(n.min)).map((n) => <NavLink key={n.to} to={n.to} end={n.to === '/'}>{n.label}</NavLink>)}
          </nav>
          <div className="me">
            <strong>{user.fullName}</strong>
            <small>{ROLE_LABELS[user.role]}</small>
            {desktop
              ? <button className="btn ghost" onClick={async () => { if (confirm('إلغاء تفعيل هذا الحاسوب؟ ستحتاج إلى تفعيله من جديد.')) { await api.logout(); setUser(null); } }}>إلغاء تفعيل هذا الجهاز</button>
              : <button className="btn ghost" onClick={async () => { await api.logout(); setUser(null); }}>تسجيل الخروج</button>}
            {desktop && <small className="muted" dir="ltr">v{desktop.version}</small>}
          </div>
        </aside>
        <main>
          <button className="btn ghost menu-btn" onClick={() => setMenuOpen(!menuOpen)} aria-label="القائمة">☰</button>
          <Routes>
            <Route path="/" element={<Overview />} />
            <Route path="/users" element={guard('ADMIN', <Users />)} />
            <Route path="/telegram" element={guard('ADMIN', <TelegramStats />)} />
            <Route path="/news" element={<News />} />
            <Route path="/activities" element={<Activities />} />
            <Route path="/lectures" element={<Lectures />} />
            <Route path="/devices" element={guard('ADMIN', <Devices />)} />
            <Route path="/polls" element={guard('ADMIN', <Polls />)} />
            <Route path="/volunteers" element={guard('ADMIN', <Volunteers />)} />
            <Route path="/media" element={<Media />} />
            <Route path="/donations" element={guard('ADMIN', <Donations />)} />
            <Route path="/notifications" element={<Notifications />} />
            <Route path="/settings" element={guard('SUPER_ADMIN', <Settings />)} />
            <Route path="/audit" element={guard('ADMIN', <Audit />)} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </main>
      </div>
    </AuthCtx.Provider>
  );
}
