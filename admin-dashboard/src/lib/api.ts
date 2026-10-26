// API client for the admin dashboard (browser) and the Windows admin app.
import { desktop, desktopFetch, desktopStore } from './desktop';
// The access token is kept in memory only; the (short-lived, admin) refresh token in sessionStorage,
// so closing the tab ends the session.

export type Role = 'SUPER_ADMIN' | 'ADMIN' | 'EDITOR' | 'VOLUNTEER' | 'USER';
export interface AdminUser { id: string; fullName: string; email: string; role: Role }

export class ApiError extends Error {
  constructor(public status: number, public code: string) { super(code); }
}

const RANK: Record<Role, number> = { USER: 0, VOLUNTEER: 1, EDITOR: 2, ADMIN: 3, SUPER_ADMIN: 4 };
export const atLeast = (role: Role | undefined, min: Role) => !!role && RANK[role] >= RANK[min];

const REFRESH_KEY = 'adm_rt';

export class ApiClient {
  private access: string | null = null;
  private refreshing: Promise<boolean> | null = null;
  onSessionEnd: () => void = () => {};

  constructor(private base: string, private store: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>, private f: typeof fetch = (...a) => fetch(...a)) {}

  get hasSession() { return !!this.store.getItem(REFRESH_KEY); }

  private setTokens(t: { accessToken: string; refreshToken: string }) {
    this.access = t.accessToken;
    this.store.setItem(REFRESH_KEY, t.refreshToken);
  }

  clear() {
    this.access = null;
    this.store.removeItem(REFRESH_KEY);
  }

  async login(identifier: string, password: string): Promise<AdminUser> {
    const r = await this.raw('POST', '/api/auth/login', { identifier, password, client: 'admin' }, false);
    this.setTokens(r.tokens);
    return r.user;
  }

  /** Desktop activation with a one-time 8-digit code issued by a supervisor. */
  async activate(code: string, deviceName: string): Promise<AdminUser> {
    const r = await this.raw('POST', '/api/auth/activate', { code, deviceName }, false);
    this.setTokens(r.tokens);
    return r.user;
  }

  /** Asks the association's approver (Telegram bot) to activate this computer. */
  async requestActivation(name: string, deviceName: string): Promise<{ requestId: string; secret: string }> {
    return this.raw('POST', '/api/auth/device-requests', { name, deviceName }, false);
  }

  /** Polls an activation request; stores the session once approved. */
  async pollActivation(id: string, secret: string): Promise<{ status: string; user?: AdminUser }> {
    const res = await this.f(`${this.base}/api/auth/device-requests/${encodeURIComponent(id)}`, { headers: { 'x-request-secret': secret } });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new ApiError(res.status, data?.error ?? `http_${res.status}`);
    if (data.status === 'approved' && data.tokens) this.setTokens(data.tokens);
    return data;
  }

  async logout() {
    const rt = this.store.getItem(REFRESH_KEY);
    if (rt) await this.raw('POST', '/api/auth/logout', { refreshToken: rt }, false).catch(() => {});
    this.clear();
  }

  /** Single-flight refresh shared by concurrent requests. */
  private refresh(): Promise<boolean> {
    if (!this.refreshing) {
      this.refreshing = (async () => {
        const rt = this.store.getItem(REFRESH_KEY);
        if (!rt) return false;
        try {
          const r = await this.raw('POST', '/api/auth/refresh', { refreshToken: rt }, false);
          this.setTokens(r.tokens);
          return true;
        } catch {
          this.clear();
          return false;
        } finally {
          setTimeout(() => { this.refreshing = null; }, 0);
        }
      })();
    }
    return this.refreshing;
  }

  private async raw(method: string, path: string, body?: unknown, auth = true): Promise<any> {
    const headers: Record<string, string> = {};
    if (body !== undefined && !(body instanceof FormData)) headers['content-type'] = 'application/json';
    if (auth && this.access) headers.authorization = `Bearer ${this.access}`;
    const res = await this.f(this.base + path, {
      method, headers,
      body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new ApiError(res.status, data?.error ?? `http_${res.status}`);
    return data;
  }

  async req<T = any>(method: string, path: string, body?: unknown): Promise<T> {
    if (!this.access && this.hasSession) await this.refresh();
    try {
      return await this.raw(method, path, body);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401 && (await this.refresh())) return this.raw(method, path, body);
      if (e instanceof ApiError && e.status === 401) this.onSessionEnd();
      throw e;
    }
  }

  get = <T = any>(p: string) => this.req<T>('GET', p);
  post = <T = any>(p: string, b?: unknown) => this.req<T>('POST', p, b ?? {});
  patch = <T = any>(p: string, b: unknown) => this.req<T>('PATCH', p, b);
  put = <T = any>(p: string, b: unknown) => this.req<T>('PUT', p, b);
  del = <T = any>(p: string) => this.req<T>('DELETE', p);
}

const BASE = (desktop?.apiBase ?? (import.meta.env.VITE_API_BASE_URL as string | undefined))?.replace(/\/$/, '') ?? '';
// Windows app: requests and the (encrypted, long-lived) device session live in the main process.
// Browser: refresh token in sessionStorage, so closing the tab ends the session.
export const api = desktop
  ? new ApiClient(BASE, desktopStore, desktopFetch)
  : new ApiClient(BASE, typeof sessionStorage !== 'undefined' ? sessionStorage : new Map() as never);
export const mediaUrl = (u: string | null | undefined) => (!u ? '' : u.startsWith('/') ? BASE + u : u);

const ERRORS: Record<string, string> = {
  invalid_credentials: 'بيانات الدخول غير صحيحة',
  insufficient_role: 'ليست لديك صلاحية لهذا الإجراء',
  account_locked: 'الحساب مقفل مؤقتاً',
  rate_limited: 'محاولات كثيرة، يرجى الانتظار',
  validation_failed: 'يرجى التحقق من الحقول',
  poll_locked_after_votes: 'لا يمكن تعديل قواعد التصويت بعد بدء الاقتراع',
  cannot_extend_after_votes: 'لا يمكن تمديد التصويت بعد بدء الاقتراع',
  cannot_assign_role: 'لا يمكنك منح هذه الصفة',
  cannot_modify_self: 'لا يمكنك تعديل حسابك من هنا',
  cannot_modify_higher_role: 'لا يمكنك تعديل حساب بصلاحية أعلى',
  last_super_admin: 'يجب الإبقاء على مشرف عام واحد على الأقل',
  r2_not_configured: 'تخزين الملفات (R2) غير مفعّل. أضف رابطاً خارجياً بدلاً من ذلك.',
  unsupported_file_type: 'نوع الملف غير مدعوم (JPEG / PNG / WebP / MP4 فقط)',
  file_too_large: 'حجم الملف كبير جداً',
  invalid_ccp: 'رقم الحساب البريدي غير صالح',
  invalid_rib: 'رقم RIB غير صالح',
  volunteer_not_approved: 'يجب قبول المتطوع أولاً',
  ends_before_start: 'تاريخ النهاية قبل تاريخ البداية',
  invalid_code: 'رمز التفعيل غير صحيح أو منتهي الصلاحية',
  invalid_name: 'الاسم يحتوي على رموز غير مسموحة',
  not_found: 'العنصر غير موجود',
};
export const errorText = (e: unknown) => (e instanceof ApiError ? ERRORS[e.code] ?? `خطأ: ${e.code}` : 'تعذّر الاتصال بالخادم');
