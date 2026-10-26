# Deployment (Cloudflare free tier)

Prerequisites: a Cloudflare account, Node 22, `npx wrangler login` (or a `CLOUDFLARE_API_TOKEN`).

## 1. Database and API (Worker)
```bash
cd backend && npm ci
npx wrangler d1 create association_db          # copy database_id into wrangler.toml
CI=true npx wrangler d1 migrations apply association_db --remote
# secrets (never commit them)
openssl rand -hex 32 | npx wrangler secret put JWT_SECRET
npx wrangler secret put TELEGRAM_BOT_TOKEN
openssl rand -hex 32 | npx wrangler secret put TELEGRAM_WEBHOOK_SECRET   # keep a copy for setup-webhook.sh
npx wrangler secret put TELEGRAM_ADMIN_CHANNEL_ID
# optional
npx wrangler secret put FCM_SERVICE_ACCOUNT_JSON      # paste the whole JSON
npx wrangler secret put RESEND_API_KEY                # + set EMAIL_FROM in wrangler.toml
npx wrangler deploy
```
Edit `[vars]` in `wrangler.toml`: `TELEGRAM_BOT_USERNAME`, `ALLOWED_ORIGINS` (admin dashboard URL),
`PUBLIC_BASE_URL` (API URL). A custom domain (e.g. `api.example.org`) can be attached in
Workers → Settings → Domains. HTTPS is automatic.

Optional media storage: `npx wrangler r2 bucket create association-media`, uncomment `[[r2_buckets]]`, redeploy.

## 2. Telegram
Follow `telegram-bot/README.md`, then run `telegram-bot/setup-webhook.sh`.

## 3. First administrator
Create the first super administrator and print its one-time activation code (valid 48 h):
`backend/scripts/create-first-admin.sh "Name"` — type the code once in the supervisors' app.
Further supervisors get their codes from the app (Devices & codes). To give an existing member
account a staff role instead, use `backend/scripts/promote-admin.sh you@example.org`.

## 4. Admin dashboard (Cloudflare Pages)
```bash
cd admin-dashboard && npm ci
VITE_API_BASE_URL=https://api.example.org npm run build
npx wrangler pages deploy dist --project-name association-admin
```
Add the Pages URL to `ALLOWED_ORIGINS` and redeploy the Worker. Consider protecting the
dashboard further with Cloudflare Access (free for up to 50 users).

## 5. Download website (Cloudflare Pages)
Set `<meta name="api-base">` in `distribution-website/index.html` to the API URL, then
`npx wrangler pages deploy distribution-website --project-name association-site`.

## 6. Android release
See `docs/ANDROID_RELEASE.md`. After publishing a release, set in Admin → Settings:
`latest_app_version`, `latest_app_version_code`, `apk_url` (the GitHub Release asset URL) and
`apk_sha256` (from the `.sha256` file). The website reads them live.

## 7. Firebase (optional, push notifications)
1. Create a Firebase project → add an Android app with package `dz.association.app`.
2. From the app config, set repository **variables** `FIREBASE_APP_ID`, `FIREBASE_API_KEY`,
   `FIREBASE_PROJECT_ID`, `FIREBASE_SENDER_ID` (these are public client identifiers; no
   `google-services.json` is needed or committed).
3. Project settings → Service accounts → generate a key → `wrangler secret put FCM_SERVICE_ACCOUNT_JSON`.
Without these, notifications are stored and shown in-app and marked `not_configured` — never reported as sent.

## Fallback API address (recommended)

Some mobile networks are slow to reach, or filter, `*.workers.dev`. The apps can switch automatically
to an equivalent address on another domain. `backend/gateway` is a free Cloudflare Pages project
that forwards every request to the Worker through a service binding (no code duplication, no secrets):

```bash
cd backend
npx wrangler pages project create association-gateway --production-branch main   # once
cd gateway && npx --prefix .. wrangler pages deploy --branch main
curl https://association-gateway.pages.dev/api/health    # → {"ok":true}
```

Then build the apps with `-PAPI_FALLBACK_URLS=https://association-gateway.pages.dev/`
(or the repository variable `API_FALLBACK_URLS` in CI). The app tries the address that last worked
first; only requests that provably never reached the server (or GET requests) are moved to the other
address, so a vote or registration is never sent twice.

## End-to-end check of the whole API

`backend/scripts/e2e.mjs` exercises every feature (supervisor activation through the bot, activation codes,
registration/login/refresh, Telegram phone verification, voting rules, news, activities, volunteers, media,
donations, notifications, RBAC, password and account deletion). It creates test data, so it refuses to run
against a deployed URL; run it against a local Worker:

```bash
cd backend
yes | npx wrangler d1 migrations apply association_db --local
npx wrangler dev --local --var JWT_SECRET:<32+ chars> --var TELEGRAM_WEBHOOK_SECRET:<secret> --var TELEGRAM_APPROVER_IDS:777000 &
BASE=http://127.0.0.1:8787 WEBHOOK_SECRET=<secret> APPROVER=777000 node scripts/e2e.mjs
```
