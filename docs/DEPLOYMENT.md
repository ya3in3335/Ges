# Deployment (Cloudflare free tier)

Prerequisites: a Cloudflare account, Node 22, `npx wrangler login` (or a `CLOUDFLARE_API_TOKEN`).

## 1. Database and API (Worker)
```bash
cd backend && npm ci
npx wrangler d1 create association_db          # copy database_id into wrangler.toml
npx wrangler d1 migrations apply association_db --remote
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
Register normally in the app (or `POST /api/auth/register`), then:
`backend/scripts/promote-admin.sh you@example.org`

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
