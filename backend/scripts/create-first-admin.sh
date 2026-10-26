#!/usr/bin/env bash
# Creates the first SUPER_ADMIN supervisor and prints a one-time activation code for the
# supervisors' app (valid 48 h). Later supervisors are created from the app itself.
#   ./scripts/create-first-admin.sh "Name"            # production D1
#   ./scripts/create-first-admin.sh "Name" --local    # local dev D1
set -euo pipefail
NAME="${1:?usage: create-first-admin.sh <name> [--local]}"
MODE="${2:---remote}"
[[ "$NAME" != *"'"* ]] || { echo "name must not contain quotes"; exit 1; }
cd "$(dirname "$0")/.."
UID_=$(cat /proc/sys/kernel/random/uuid)
CODE=$(od -An -N8 -tu8 /dev/urandom | tr -d ' ' | tail -c 8)
while [ ${#CODE} -lt 8 ]; do CODE="0$CODE"; done
HASH=$(printf 'activation:%s' "$CODE" | sha256sum | cut -d' ' -f1)
NOW=$(($(date +%s) * 1000)); EXP=$((NOW + 48 * 3600 * 1000))
CI=true npx wrangler d1 execute association_db "$MODE" --command "
INSERT INTO users (id, full_name, email, phone_e164, password_hash, role, status, is_staff_only, created_at, updated_at)
VALUES ('$UID_', '$NAME', 'staff-$UID_@staff.invalid', 'staff:$UID_', '!', 'SUPER_ADMIN', 'active', 1, $NOW, $NOW);
INSERT INTO activation_codes (id, user_id, code_hash, created_at, expires_at) VALUES ('$(cat /proc/sys/kernel/random/uuid)', '$UID_', '$HASH', $NOW, $EXP);" >/dev/null
echo "Activation code for $NAME (SUPER_ADMIN, single use, 48 h): $CODE"
