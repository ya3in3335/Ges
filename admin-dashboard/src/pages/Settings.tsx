import { useEffect, useState } from 'react';
import { api, errorText } from '../lib/api';
import { Field, Page, State, useLoad } from '../lib/ui';

const KEYS: [string, string, boolean?][] = [
  ['association_name_ar', 'الاسم الرسمي للجمعية (عربية)'], ['association_name_fr', 'Nom officiel (français)'],
  ['association_short_description_ar', 'وصف قصير (عربية)'], ['association_short_description_fr', 'Description courte (français)'],
  ['about_ar', 'التعريف بالجمعية (عربية)', true], ['about_fr', 'Présentation (français)', true],
  ['contact_phone', 'هاتف الاتصال'], ['contact_email', 'البريد الإلكتروني'], ['contact_address_ar', 'العنوان (عربية)'], ['contact_address_fr', 'Adresse (français)'],
  ['website_url', 'الموقع (https)'], ['facebook_url', 'Facebook (https)'], ['instagram_url', 'Instagram (https)'], ['youtube_url', 'YouTube (https)'], ['telegram_channel_url', 'قناة تيليغرام العامة (https)'],
  ['latest_app_version', 'آخر إصدار للتطبيق (مثال 1.0.0)'], ['latest_app_version_code', 'رقم الإصدار (versionCode)'], ['apk_url', 'رابط تنزيل APK (https)'], ['apk_sha256', 'بصمة SHA-256 لملف APK'], ['min_supported_version_code', 'أدنى إصدار مدعوم'],
];

export default function Settings() {
  const { data, error, loading, reload } = useLoad(() => api.get<{ settings: Record<string, string> }>('/api/settings'));
  const [form, setForm] = useState<Record<string, string>>({});
  const [msg, setMsg] = useState<string | null>(null);
  useEffect(() => { if (data) setForm(Object.fromEntries(KEYS.map(([k]) => [k, data.settings[k] ?? '']))); }, [data]);

  const save = async () => {
    setMsg(null);
    try { await api.put('/api/admin/settings', form); setMsg('تم الحفظ'); } catch (e) { setMsg(errorText(e)); }
  };

  return (
    <Page title="إعدادات الجمعية والتطبيق">
      <p className="note">تُعرض هذه البيانات للعموم في التطبيق والموقع. اترك الحقل فارغاً إذا لم تتوفر المعلومة الرسمية بعد.</p>
      <State loading={loading && !data} error={error} onRetry={reload} />
      {data && <div className="form card">
        {KEYS.map(([k, label, multi]) => <Field key={k} label={label}>
          {multi ? <textarea rows={5} dir={k.endsWith('_fr') ? 'ltr' : undefined} value={form[k] ?? ''} onChange={(e) => setForm({ ...form, [k]: e.target.value })} />
            : <input dir={k.endsWith('_fr') || /url|email|phone|version/.test(k) ? 'ltr' : undefined} value={form[k] ?? ''} onChange={(e) => setForm({ ...form, [k]: e.target.value })} />}
        </Field>)}
        {msg && <div className="state">{msg}</div>}
        <button className="btn" onClick={save}>حفظ</button>
      </div>}
    </Page>
  );
}
