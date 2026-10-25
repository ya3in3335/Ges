import type { Ctx } from './env';

export type Handler = (ctx: Ctx) => Promise<Response> | Response;
type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

interface Route {
  method: Method;
  parts: string[];
  handler: Handler;
}

/** Minimal path router: supports static segments and `:param` segments. */
export class Router {
  private routes: Route[] = [];

  on(method: Method, path: string, handler: Handler): this {
    this.routes.push({ method, parts: path.split('/').filter(Boolean), handler });
    return this;
  }
  get(p: string, h: Handler) { return this.on('GET', p, h); }
  post(p: string, h: Handler) { return this.on('POST', p, h); }
  put(p: string, h: Handler) { return this.on('PUT', p, h); }
  patch(p: string, h: Handler) { return this.on('PATCH', p, h); }
  delete(p: string, h: Handler) { return this.on('DELETE', p, h); }

  match(method: string, pathname: string): { handler: Handler; params: Record<string, string> } | 'method_not_allowed' | null {
    const segs = pathname.split('/').filter(Boolean);
    let pathMatched = false;
    for (const r of this.routes) {
      if (r.parts.length !== segs.length) continue;
      const params: Record<string, string> = {};
      let ok = true;
      for (let i = 0; i < segs.length; i++) {
        const p = r.parts[i]!;
        const s = segs[i]!;
        if (p.startsWith(':')) {
          try { params[p.slice(1)] = decodeURIComponent(s); } catch { ok = false; break; }
        } else if (p !== s) { ok = false; break; }
      }
      if (!ok) continue;
      pathMatched = true;
      if (r.method === method) return { handler: r.handler, params };
    }
    return pathMatched ? 'method_not_allowed' : null;
  }
}
