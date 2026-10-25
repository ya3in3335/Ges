#!/usr/bin/env bash
# Registers the Worker as the bot's webhook. The bot logic itself lives in the Worker
# (backend/src/routes/verification.ts) so there is nothing else to host.
#
# Usage:
#   TELEGRAM_BOT_TOKEN=... TELEGRAM_WEBHOOK_SECRET=... API_BASE_URL=https://api.example.org ./setup-webhook.sh
# Use the same TELEGRAM_WEBHOOK_SECRET value you stored with `wrangler secret put`.
set -euo pipefail
: "${TELEGRAM_BOT_TOKEN:?missing}" "${TELEGRAM_WEBHOOK_SECRET:?missing}" "${API_BASE_URL:?missing}"
[[ "$API_BASE_URL" == https://* ]] || { echo "API_BASE_URL must be https"; exit 1; }
[[ "$TELEGRAM_WEBHOOK_SECRET" =~ ^[A-Za-z0-9_-]{32,256}$ ]] || { echo "TELEGRAM_WEBHOOK_SECRET must be 32-256 chars of A-Z a-z 0-9 _ -"; exit 1; }
API="https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}"

curl -fsS "$API/setWebhook" -H 'content-type: application/json' -d "{
  \"url\": \"${API_BASE_URL%/}/telegram/webhook\",
  \"secret_token\": \"${TELEGRAM_WEBHOOK_SECRET}\",
  \"allowed_updates\": [\"message\"],
  \"drop_pending_updates\": true,
  \"max_connections\": 10
}"; echo

curl -fsS "$API/setMyCommands" -H 'content-type: application/json' -d '{"commands":[{"command":"start","description":"تأكيد رقم الهاتف / Confirmer le numéro"}]}' >/dev/null
curl -fsS "$API/setMyDescription" -H 'content-type: application/json' \
  -d '{"description":"بوت تأكيد أرقام الهواتف لتطبيق الجمعية. ابدأ من التطبيق: حسابي ← تأكيد رقم الهاتف عبر تيليغرام."}' >/dev/null
curl -fsS "$API/getWebhookInfo"; echo
