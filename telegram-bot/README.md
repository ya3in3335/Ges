# Telegram verification bot

The bot has **no separate server**: Telegram delivers updates to the Worker endpoint
`POST /telegram/webhook` (see `backend/src/routes/verification.ts`). This folder only holds
setup scripts.

## 1. Create the bot
1. In Telegram, open **@BotFather** → `/newbot`. Pick a name and a username ending in `bot`.
2. Copy the token. Store it only as a Worker secret: `npx wrangler secret put TELEGRAM_BOT_TOKEN`.
3. Put the username (without `@`) in `backend/wrangler.toml` → `TELEGRAM_BOT_USERNAME`.
4. Generate a webhook secret (e.g. `openssl rand -hex 32`) and store it: `npx wrangler secret put TELEGRAM_WEBHOOK_SECRET`.
5. After deploying the Worker, run `./setup-webhook.sh` (see the header for variables).

## 2. Private admin channel
1. Create a **private** channel.
2. Channel → Administrators → add the bot, allow **Post messages** only.
3. Find the channel id (starts with `-100`): forward a channel post to `@userinfobot`, or call
   `getUpdates` once before setting the webhook.
4. `npx wrangler secret put TELEGRAM_ADMIN_CHANNEL_ID`, then check with `./test-channel.sh`.

Channel messages contain the internal user id, status, method, date and the total of verified
users — never phone numbers, tokens or passwords. D1 stays the authoritative record; failed
posts are retried by the cron trigger with exponential backoff (max 8 attempts).

## Flow and checks
See `docs/TELEGRAM_VERIFICATION.md`.
