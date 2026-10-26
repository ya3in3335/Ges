// Bridge to the Windows app (Electron preload). Undefined in the browser.
// The main process performs all HTTP requests (no CORS, API hosts allow-listed, automatic
// fallback address) and keeps the session encrypted with the OS key store.

export type WireBody = string | { formData: { name: string; value?: string; filename?: string; type?: string; data?: ArrayBuffer }[] } | undefined;

export interface DesktopBridge {
  apiBase: string;
  deviceName: string;
  version: string;
  store: { get(k: string): string | null; set(k: string, v: string): void; remove(k: string): void };
  request(url: string, init: { method: string; headers: Record<string, string>; body: WireBody }): Promise<{ status: number; headers: Record<string, string>; body: ArrayBuffer }>;
  saveFile(defaultName: string, content: string): Promise<boolean>;
}

declare global { interface Window { desktop?: DesktopBridge } }

export const desktop: DesktopBridge | undefined = typeof window !== 'undefined' ? window.desktop : undefined;

async function toWire(body: BodyInit | null | undefined): Promise<WireBody> {
  if (body == null) return undefined;
  if (typeof body === 'string') return body;
  if (body instanceof FormData) {
    const formData: Exclude<WireBody, string | undefined>['formData'] = [];
    for (const [name, v] of body.entries()) {
      if (typeof v === 'string') formData.push({ name, value: v });
      else formData.push({ name, filename: v.name, type: v.type, data: await v.arrayBuffer() });
    }
    return { formData };
  }
  throw new Error('unsupported_body');
}

/** fetch() replacement routed through the desktop main process. */
export const desktopFetch: typeof fetch = async (input, init = {}) => {
  const headers: Record<string, string> = {};
  new Headers(init.headers).forEach((v, k) => { headers[k] = v; });
  const r = await desktop!.request(String(input), { method: init.method ?? 'GET', headers, body: await toWire(init.body) });
  if (r.status === 0) throw new TypeError('network_error');
  return new Response(r.body, { status: r.status, headers: r.headers });
};

export const desktopStore: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> = {
  getItem: (k) => desktop!.store.get(k),
  setItem: (k, v) => desktop!.store.set(k, v),
  removeItem: (k) => desktop!.store.remove(k),
};

/** Saves text to a file: native "Save as" on Windows, a download in the browser. */
export async function saveText(name: string, content: string) {
  if (desktop) return desktop.saveFile(name, content);
  const url = URL.createObjectURL(new Blob([content], { type: 'text/csv;charset=utf-8' }));
  const a = Object.assign(document.createElement('a'), { href: url, download: name });
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  return true;
}
