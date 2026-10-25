# Architecture

```
Android app ──HTTPS/JSON──▶ Cloudflare Worker (backend/src) ──▶ D1 (SQLite)
                                 │   ├─ R2 (optional media)
Admin dashboard (Pages) ─────────┤   ├─ Telegram Bot API (bot replies, admin channel)
Download site (Pages) ── /api/settings  └─ FCM HTTP v1 (optional push)
Telegram ──webhook──▶ /telegram/webhook        Cron (10 min): outbox retries, expiry, cleanup
```

## Backend (`backend/`)
- `src/index.ts` route table, auth resolution, CORS, cron; `src/router.ts` tiny router (no framework).
- `src/routes/*` one module per domain; `src/lib/*` crypto, JWT, passwords, phone normalisation, RBAC, rate limiting, audit; `src/services/*` Telegram, channel outbox, FCM, e-mail.
- `migrations/0001_initial.sql` versioned schema (apply with `wrangler d1 migrations apply`).
- Tests run the real migration and SQL on `node:sqlite` through a D1-compatible shim (`test/d1shim.ts`).

## Android (`android-app/app/src/main/java/dz/association/app`)
- `core/network` Retrofit API + DTOs mirroring the backend, auth interceptor and single-flight refresh authenticator.
- `core/auth` Keystore-encrypted token store, `SessionManager`.
- `core/db` Room offline cache (JSON payloads per key), `core/prefs` DataStore, `core/push` FCM + WorkManager.
- `data/` repositories (network-first with cache fallback for public content only).
- `ui/` Compose screens (MVVM, `StateFlow`, Hilt ViewModels), `ui/navigation` (5 bottom tabs + routes + notification deep links), `ui/intro` client animation.

## API summary
Public: `GET /api/home, /api/settings, /api/donations, /api/news[/:id], /api/activities[/:id], /api/media, /api/polls[/:id]`.
Auth: `POST /api/auth/{register,login,refresh,logout,password/forgot,password/reset,email/send,email/verify}`.
Account: `GET|PATCH|DELETE /api/me`, `POST /api/me/password`, `GET /api/me/{notifications,activities}`, `POST /api/devices[/unregister]`.
Participation: `POST /api/verification/telegram`, `GET /api/verification/telegram/:id`, `POST /api/polls/:id/vote`, `POST /api/activities/:id/{register,cancel}`, `POST|GET|DELETE /api/volunteers[/me]`.
Admin: `/api/admin/{overview,telegram-stats,users,audit,news,activities,polls,volunteers,media,donations,settings,notifications}` (see `src/index.ts`).
Errors: `{ "error": "<code>", "details": … }` with HTTP status; the app maps codes to translated messages.
