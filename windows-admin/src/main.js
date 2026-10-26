'use strict';
// Windows admin app: hosts the shared admin dashboard in a locked-down window.
// - The page is served from the app package through the app:// protocol (no remote code).
// - All HTTP goes through this process: only the association's API addresses, with fallback.
// - The device session (long-lived refresh token) is encrypted with the Windows key store (DPAPI).

const { app, BrowserWindow, dialog, ipcMain, net, protocol, safeStorage, session, shell } = require('electron');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { hostsFrom, sendWithFailover } = require('./net');

const config = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'config.json'), 'utf8'));
const hosts = hostsFrom(config);
const WEB = path.join(__dirname, '..', 'web');

protocol.registerSchemesAsPrivileged([{ scheme: 'app', privileges: { standard: true, secure: true, supportFetchAPI: true } }]);

if (!app.requestSingleInstanceLock()) app.quit();

// ───────────── Encrypted local store ─────────────
const storeFile = () => path.join(app.getPath('userData'), 'session.bin');
let store = null;
function loadStore() {
  if (store) return store;
  store = {};
  try {
    const raw = fs.readFileSync(storeFile());
    store = JSON.parse(safeStorage.isEncryptionAvailable() ? safeStorage.decryptString(raw) : raw.toString('utf8'));
  } catch { /* first run or unreadable: start empty */ }
  return store;
}
function saveStore() {
  const text = JSON.stringify(store || {});
  const data = safeStorage.isEncryptionAvailable() ? safeStorage.encryptString(text) : Buffer.from(text, 'utf8');
  fs.mkdirSync(path.dirname(storeFile()), { recursive: true });
  fs.writeFileSync(storeFile(), data, { mode: 0o600 });
}

const netState = { preferred: 0 };
function deviceName() { return `Windows - ${os.hostname()}`.slice(0, 80); }

function registerIpc() {
  ipcMain.on('info', (e) => { e.returnValue = { apiBase: config.apiBase, deviceName: deviceName(), version: app.getVersion() }; });
  ipcMain.on('store:get', (e, k) => { e.returnValue = Object.prototype.hasOwnProperty.call(loadStore(), k) ? loadStore()[k] : null; });
  ipcMain.on('store:set', (e, k, v) => { loadStore()[String(k)] = String(v); saveStore(); e.returnValue = true; });
  ipcMain.on('store:remove', (e, k) => { delete loadStore()[k]; saveStore(); e.returnValue = true; });

  ipcMain.handle('api:request', async (_e, url, init) => {
    let body;
    if (typeof init.body === 'string') body = init.body;
    else if (init.body && init.body.formData) {
      body = new FormData();
      for (const p of init.body.formData) {
        if (p.data) body.append(p.name, new Blob([p.data], { type: p.type || 'application/octet-stream' }), p.filename || 'file');
        else body.append(p.name, p.value ?? '');
      }
    }
    const headers = { ...init.headers, 'user-agent': `AssociationAdminDesktop/${app.getVersion()}` };
    if (body instanceof FormData) delete headers['content-type'];
    try {
      const res = await sendWithFailover({
        hosts, state: netState, url,
        init: { method: init.method, headers, body, signal: AbortSignal.timeout(45_000) },
        fetchImpl: (u, i) => net.fetch(u, i),
      });
      const out = {};
      res.headers.forEach((v, k) => { out[k] = v; });
      return { status: res.status, headers: out, body: await res.arrayBuffer() };
    } catch {
      return { status: 0, headers: {}, body: new ArrayBuffer(0) };
    }
  });

  ipcMain.handle('file:save', async (e, name, content) => {
    const win = BrowserWindow.fromWebContents(e.sender);
    const r = await dialog.showSaveDialog(win, { defaultPath: String(name).replace(/[\\/:*?"<>|]/g, '_'), filters: [{ name: 'CSV', extensions: ['csv'] }] });
    if (r.canceled || !r.filePath) return false;
    fs.writeFileSync(r.filePath, String(content), 'utf8');
    return true;
  });
}

// ───────────── app:// protocol (packaged files only, SPA fallback) ─────────────
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.webp': 'image/webp', '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.json': 'application/json', '.woff2': 'font/woff2' };
const CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' https: data: blob:; media-src https:; connect-src 'self'; frame-src 'none'; object-src 'none'; base-uri 'none'";
function serve(req) {
  const rel = decodeURIComponent(new URL(req.url).pathname).replace(/^\/+/, '');
  let file = path.normalize(path.join(WEB, rel));
  if (!file.startsWith(WEB) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(WEB, 'index.html');
  return new Response(fs.readFileSync(file), {
    headers: { 'content-type': TYPES[path.extname(file)] || 'application/octet-stream', 'content-security-policy': CSP, 'x-content-type-options': 'nosniff' },
  });
}

let win = null;
function createWindow() {
  win = new BrowserWindow({
    width: 1320, height: 860, minWidth: 900, minHeight: 600,
    title: 'إدارة الجمعية', backgroundColor: '#faf6f5', autoHideMenuBar: true, show: false,
    icon: path.join(WEB, 'favicon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false, spellcheck: false },
  });
  win.once('ready-to-show', () => win.show());
  // Links leave the app only to the system browser; the window never navigates away.
  win.webContents.setWindowOpenHandler(({ url }) => { if (url.startsWith('https://')) shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('app://')) { e.preventDefault(); if (url.startsWith('https://')) shell.openExternal(url); } });
  win.loadURL('app://admin/');
}

app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
app.whenReady().then(() => {
  protocol.handle('app', serve);
  session.defaultSession.setPermissionRequestHandler((_wc, _perm, cb) => cb(false));
  registerIpc();
  createWindow();
});
app.on('window-all-closed', () => app.quit());
