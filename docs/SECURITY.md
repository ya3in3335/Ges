# Security overview

No system is free of vulnerabilities; these are the controls in place and their limits.

- **Transport:** HTTPS only (Workers/Pages); Android `cleartextTrafficPermitted=false`; app loads only `https://` media.
- **Passwords:** PBKDF2-HMAC-SHA256, 100k iterations, 16-byte salt, NFKC; policy (8+ chars, letters + digits, common list); constant-time comparison; dummy hash for unknown accounts; lockout after 10 failures (15 min).
- **Sessions:** HS256 access JWT (15 min, `alg` pinned) + opaque refresh token (SHA-256 stored, rotated on each use, reuse revokes the whole family). Every request re-checks the session row and takes the **role from the database**, so logout/suspension/demotion apply immediately. Admin sessions: 1-day refresh, kept in `sessionStorage`; access token in memory.
- **Android storage:** tokens encrypted with AES-256-GCM using a non-exportable Android Keystore key; backups and device transfer excluded.
- **Authorization:** server-side RBAC (`SUPER_ADMIN > ADMIN > EDITOR > VOLUNTEER > USER`); actors can only assign roles below their own; last SUPER_ADMIN protected; full phone numbers visible to SUPER_ADMIN only.
- **Input:** zod schemas (strict where relevant), 64 KB JSON limit, parameterised SQL only (column names never from input), LIKE wildcards escaped.
- **IDOR:** user-scoped queries use the authenticated id; foreign objects return 404.
- **Voting:** eligibility re-checked server-side; `poll_participation` PK + transactional batch prevents double votes (tested under concurrency); window re-checked inside the transaction; ballots immutable via triggers; options locked after the first vote; anonymous ballots stored without user id in a `WITHOUT ROWID` table keyed by random UUID.
- **Uploads:** EDITOR+, type detected from magic bytes (JPEG/PNG/WebP/MP4; SVG/HTML refused), size limits, random keys, `nosniff` + restrictive CSP on served files.
- **Rate limits:** register, login (IP + identifier), refresh, reset, verification start, voting, registrations, volunteer applications, device registration.
- **Audit:** `admin_audit` (every admin write, actor, IP), `verification_audit`.
- **Web:** dashboard CORS allow-list, CSP / frame-ancestors / nosniff headers via `_headers`; dashboard is `noindex`.
- **Secrets:** only via `wrangler secret`, GitHub secrets/variables; `.gitignore` blocks keystores, `.dev.vars`, `google-services.json`.

Recommended before launch: Cloudflare Access in front of the dashboard, WAF rate-limit rule on `/api/auth/*`, enable 2FA on all Cloudflare/GitHub/Telegram admin accounts, review the privacy policy.
