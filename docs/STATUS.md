# Delivery status

Legend: ✅ done and verified here · 🧪 covered by automated tests · 🔑 waiting for external credentials/details · ⚠️ limitation

## Implemented
| Area | Status |
|---|---|
| Client intro animation integrated without redesign (client `render()` code copied verbatim, locked-down WebView) | ✅ 🧪 (asset self-containment test; played to completion in headless Chromium in 9.5 s, no JS errors) |
| Logo-derived launcher icons, in-app logo, admin and website logos | ✅ |
| Android: home, news (search, categories, pagination, share), activities (phases, registration, capacity, map, media), voting, volunteering, account (register/login/logout/reset/delete, privacy, notification prefs, language, theme), gallery (Coil, Media3), donations, about, notifications | ✅ compiles, lint clean, debug + minified release APK build |
| Arabic default + French, full RTL | ✅ 🧪 (string parity, RTL layout, default-locale tests) |
| Offline cache of public content with offline banner; votes/verification always need the server | ✅ |
| Backend REST API, D1 schema + migration, RBAC (5 roles), rate limiting, audit logs | ✅ 🧪 |
| Telegram phone verification + private channel notifications with retry outbox | ✅ 🧪 and smoke-tested on the real Workers runtime (`wrangler dev` + local D1) |
| Voting: eligibility (verified / approved member list), anonymous ballots, one vote per account, immutable ballots, result visibility rules | ✅ 🧪 |
| Supervisors' Android app (separate APK, **no login**: one-time 8-digit activation code per device, long-lived revocable device session, codes issued and devices revoked from the app): overview, users, Telegram stats, news, activities + participants, polls (members, results), volunteers, media (links + phone upload), donations, notifications, settings, audit | ✅ compiles, lint clean, debug + minified release build · 🧪 7 tests (role gate, member accounts refused, no admin code in the members' app) |
| Web admin dashboard (optional, same modules) | ✅ 🧪 and exercised end-to-end in Chromium against the local Worker |
| Push notifications (FCM HTTP v1 from the Worker; topic subscriptions in the app) | ✅ code complete · 🔑 needs Firebase project |
| Download website (AR/FR, install guide, privacy policy draft, version/APK/SHA-256 from API) | ✅ rendered and checked in Chromium |
| GitHub Actions: Android tests, lint, debug APK, signed release + GitHub Release; backend tests + manual deploy; web build | ✅ written and YAML-validated · not yet run on GitHub |

## Deployed (production, Cloudflare free plan)
- API: `https://association-api.yacincianai31.workers.dev` (D1 `association_db`, cron every 10 min)
- Telegram bot @forge_flow_bot connected by webhook (secret-header protected); private admin channel linked (test message delivered).
- Supervisor devices are approved from the bot by the configured approver (TELEGRAM_APPROVER_IDS).

## Tested here (actual runs)
- Backend: 108 Vitest tests passing (real SQL via a `node:sqlite` D1 shim) + `tsc` + Wrangler bundle.
- Real Workers runtime: migration applied to local D1; register → start verification → `/start` → shared contact → status `verified` → `/api/me` shows `phoneVerified: true, telegramLinked: true`.
- Admin dashboard: typecheck, 4 tests, production build, Chromium E2E (login, overview stats, create news, create poll, users, Telegram stats, mobile layout).
- Android: 19 unit tests (deep links, intro URL policy, phone validation, verification state mapping, token refresh + reuse handling, encrypted token storage, error mapping, strings parity, RTL, intro asset, screenshots), `lintDebug`, `assembleDebug`, `assembleRelease` (R8).

## Not tested here
- The app on a physical device or emulator (no emulator in this environment). The screenshots on the website are Robolectric renders of real composables with sample content.
- Real Telegram, Firebase, Cloudflare production, R2 uploads and Resend e-mail (no credentials).

## 🔑 Needed from the association
- **Official name** (the logo appears to read «التنسيقية … بوهران»; the middle word could not be read with certainty, so the app label is only «التنسيقية» until confirmed). Set it in Admin → Settings and in `android-app/app/src/main/res/values*/strings.xml` (`app_name`).
- About text, contact details, social links, CCP/RIB and donation instructions (all empty until entered in the dashboard — nothing was invented).
- Telegram bot token + private channel; Cloudflare account; Firebase project (optional); release keystore; domain names.
- A lawyer/board review of the privacy-policy draft.

## ⚠️ Known limitations
- Telegram contact sharing proves control of a Telegram account bound to that number — not legal identity or SIM ownership. One verified account ≠ one unique person; use the member list for formal elections.
- PBKDF2-SHA256 at 100 000 iterations (the Workers maximum) is used instead of Argon2/bcrypt.
- Rate limiting is a D1 fixed window (simple, free); for heavy abuse add a Cloudflare WAF rate-limit rule.
- Anonymous ballots are unlinkable in the database, but an operator with database access and request logs at the exact moment of voting could still correlate timing.
- Password reset requires a linked Telegram account (or Resend e-mail if configured).
- Application ID is `dz.association.app` — change it before the first public release if desired (it cannot change afterwards without a new app).
