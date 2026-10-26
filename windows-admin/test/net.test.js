'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { hostsFrom, apiIndex, sendWithFailover } = require('../src/net');

const hosts = hostsFrom({ apiBase: 'https://api.example.dev', fallbacks: ['https://gw.example.dev'] });
const json = (status = 200) => new Response('{"ok":true}', { status, headers: { 'content-type': 'application/json' } });
const refused = () => Object.assign(new Error('net::ERR_CONNECTION_REFUSED'), { code: 'ECONNREFUSED' });

test('only API hosts over https are allowed', () => {
  assert.strictEqual(apiIndex(hosts, 'https://api.example.dev/api/me'), 0);
  assert.strictEqual(apiIndex(hosts, 'https://gw.example.dev/api/me'), 1);
  assert.strictEqual(apiIndex(hosts, 'https://evil.example/api/me'), -1);
  assert.strictEqual(apiIndex(hosts, 'http://api.example.dev/api/me'), -1);
});

test('unreachable primary falls back and is remembered', async () => {
  const state = { preferred: 0 };
  const seen = [];
  const fetchImpl = async (u) => { seen.push(new URL(u).host); if (u.includes('api.example.dev')) throw refused(); return json(); };
  await sendWithFailover({ hosts, state, url: 'https://api.example.dev/api/admin/overview', init: { method: 'GET' }, fetchImpl });
  assert.deepStrictEqual(seen, ['api.example.dev', 'gw.example.dev']);
  assert.strictEqual(state.preferred, 1);
});

test('a POST that may have reached the server is not repeated', async () => {
  const state = { preferred: 0 };
  let calls = 0;
  const fetchImpl = async () => { calls++; throw Object.assign(new Error('socket hang up'), { code: 'ECONNRESET_AFTER_SEND' }); };
  await assert.rejects(sendWithFailover({ hosts, state, url: 'https://api.example.dev/api/admin/polls', init: { method: 'POST' }, fetchImpl }));
  assert.strictEqual(calls, 1);
});

test('filter pages (HTML instead of JSON) move to the other address', async () => {
  const state = { preferred: 0 };
  const fetchImpl = async (u) => (u.includes('api.example.dev') ? new Response('<html>blocked</html>', { status: 403, headers: { 'content-type': 'text/html' } }) : json());
  const res = await sendWithFailover({ hosts, state, url: 'https://api.example.dev/api/lectures', init: { method: 'GET' }, fetchImpl });
  assert.strictEqual(res.status, 200);
});

test('other hosts are refused', async () => {
  await assert.rejects(sendWithFailover({ hosts, state: { preferred: 0 }, url: 'https://evil.example/x', init: { method: 'GET' }, fetchImpl: async () => json() }), /forbidden_host/);
});
