# تطبيق الجمعية — Association app

Android application, Cloudflare backend, Telegram phone verification, admin dashboard and APK
download website for the association whose logo and intro animation are in `branding/`.

| Folder | What | Stack |
|---|---|---|
| `android-app/` | Android app (Arabic default, RTL; French) | Kotlin, Jetpack Compose, M3, Hilt, Retrofit, Room, DataStore, WorkManager, Media3, Coil, FCM |
| `backend/` | REST API + Telegram bot webhook + cron | Cloudflare Workers (TypeScript), D1, optional R2 |
| `admin-dashboard/` | Web administration (Arabic RTL) | React, TypeScript, Vite → Cloudflare Pages |
| `distribution-website/` | Official APK download site | Static HTML/CSS/JS → Cloudflare Pages |
| `telegram-bot/` | Bot webhook / channel setup scripts | bash + Bot API |
| `branding/` | Client's original animation sources (ZIP contents) and derived logo | — |
| `tools/` | Rebuild logo assets and the intro page from the client's files | Python (Pillow) |
| `docs/` | Architecture, deployment, security, costs, status | — |

Start with **[docs/STATUS.md](docs/STATUS.md)** (what is done, tested, and waiting for credentials),
then **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)**.

## Quick local run
```bash
# API (local D1)
cd backend && npm ci && cp .dev.vars.example .dev.vars
npx wrangler d1 migrations apply association_db --local && npx wrangler dev
# Admin dashboard
cd admin-dashboard && npm ci && VITE_API_BASE_URL=http://localhost:8787 npm run dev
# Android (needs Android SDK 35, JDK 17+)
cd android-app && ./gradlew :app:testDebugUnitTest :app:assembleDebug -PAPI_BASE_URL=https://your-api.example/
```

## Tests
```bash
cd backend && npm test                      # 108 tests (auth, Telegram abuse cases, voting, RBAC, migrations…)
cd admin-dashboard && npm test              # API client / session tests
cd android-app && ./gradlew :app:testDebugUnitTest   # 19 JVM/Robolectric tests
```
