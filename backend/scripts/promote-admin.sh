#!/usr/bin/env bash
# Bootstraps the first SUPER_ADMIN. Register the account normally (app or API) first, then:
#   ./scripts/promote-admin.sh admin@example.org            # production D1
#   ./scripts/promote-admin.sh admin@example.org --local    # local dev D1
# Afterwards, manage roles from the admin dashboard.
set -euo pipefail
EMAIL="${1:?usage: promote-admin.sh <email> [--local]}"
MODE="${2:---remote}"
[[ "$EMAIL" =~ ^[^@\'\"[:space:]]+@[^@\'\"[:space:]]+$ ]] || { echo "invalid e-mail"; exit 1; }
cd "$(dirname "$0")/.."
npx wrangler d1 execute association_db "$MODE" --command \
  "UPDATE users SET role = 'SUPER_ADMIN', updated_at = unixepoch() * 1000 WHERE email = '${EMAIL,,}' AND status = 'active'; SELECT id, email, role FROM users WHERE email = '${EMAIL,,}';"
