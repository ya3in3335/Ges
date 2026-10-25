#!/usr/bin/env bash
# Sends a test message to the private admin channel to confirm the bot can post there.
# Usage: TELEGRAM_BOT_TOKEN=... TELEGRAM_ADMIN_CHANNEL_ID=-100... ./test-channel.sh
set -euo pipefail
: "${TELEGRAM_BOT_TOKEN:?missing}" "${TELEGRAM_ADMIN_CHANNEL_ID:?missing}"
curl -fsS "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" -H 'content-type: application/json' \
  -d "{\"chat_id\": \"${TELEGRAM_ADMIN_CHANNEL_ID}\", \"text\": \"✅ اختبار: البوت قادر على النشر في قناة الإدارة.\"}"; echo
