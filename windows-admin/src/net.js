'use strict';
// Network helpers for the main process (pure, unit-tested with node --test).

/** Only the association's API addresses may be called from the app. */
function hostsFrom(config) {
  return [config.apiBase, ...(config.fallbacks || [])].map((u) => new URL(u));
}

function apiIndex(hosts, url) {
  let u;
  try { u = new URL(url); } catch { return -1; }
  if (u.protocol !== 'https:') return -1;
  return hosts.findIndex((h) => h.host === u.host);
}

function rewrite(url, host) {
  const u = new URL(url);
  u.protocol = host.protocol; u.host = host.host;
  return u.toString();
}

/** Failures that prove the request never reached the API (safe to send elsewhere, even a POST). */
function neverReachedServer(err) {
  const code = (err && (err.code || (err.cause && err.cause.code) || err.message)) || '';
  return /ENOTFOUND|EAI_AGAIN|ECONNREFUSED|ENETUNREACH|EHOSTUNREACH|ERR_NAME_NOT_RESOLVED|ERR_CONNECTION_REFUSED|ERR_CONNECTION_RESET|ERR_ADDRESS_UNREACHABLE|ERR_INTERNET_DISCONNECTED|ERR_SSL|ERR_CERT|ERR_CONNECTION_TIMED_OUT|ERR_TIMED_OUT/.test(String(code));
}

/**
 * Sends an API request to the address that last worked, then to the others when it cannot be
 * reached. GET requests always move on; other methods only when they provably never left.
 * Returns { res, index } or throws the last error.
 */
async function sendWithFailover({ hosts, state, url, init, fetchImpl }) {
  const origin = apiIndex(hosts, url);
  if (origin < 0) throw new Error('forbidden_host');
  const order = [state.preferred, ...hosts.map((_, i) => i).filter((i) => i !== state.preferred)];
  const idempotent = (init.method || 'GET') === 'GET';
  let last;
  for (let n = 0; n < order.length; n++) {
    const i = order[n];
    try {
      const res = await fetchImpl(rewrite(url, hosts[i]), init);
      const type = res.headers.get('content-type') || '';
      // A filter or captive portal answers with HTML instead of the API's JSON.
      if (n < order.length - 1 && new URL(url).pathname.startsWith('/api/') && !type.includes('json') && res.status !== 204 && (idempotent || res.status < 500)) continue;
      state.preferred = i;
      return res;
    } catch (e) {
      last = e;
      if (!(idempotent || neverReachedServer(e))) throw e;
    }
  }
  throw last || new Error('unreachable');
}

module.exports = { hostsFrom, apiIndex, rewrite, neverReachedServer, sendWithFailover };
