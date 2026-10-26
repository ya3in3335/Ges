// Builds the admin dashboard (shared with the browser version) into ./web for the desktop app.
import { cpSync, readFileSync, rmSync } from 'node:fs';
import { execSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const dash = join(root, '..', 'admin-dashboard');
const { apiBase } = JSON.parse(readFileSync(join(root, 'config.json'), 'utf8'));
execSync('npx vite build', { cwd: dash, stdio: 'inherit', env: { ...process.env, VITE_API_BASE_URL: apiBase } });
rmSync(join(root, 'web'), { recursive: true, force: true });
cpSync(join(dash, 'dist'), join(root, 'web'), { recursive: true });
// Browser-hosting files are not needed in the desktop app.
for (const f of ['_headers', '_redirects']) rmSync(join(root, 'web', f), { force: true });
