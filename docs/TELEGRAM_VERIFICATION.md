# Telegram phone verification

```
App ──POST /api/verification/telegram (auth)──▶ Worker: random 32-byte token, store SHA-256 only,
                                                 TTL 10 min, cancels previous live sessions
App ◀── deep link t.me/<bot>?start=<token> ───── 
User opens Telegram → /start <token> ──webhook──▶ bind session to sender's Telegram id (first opener wins)
Bot ◀── "share my number" keyboard (request_contact) 
User taps button → message.contact ──webhook──▶ checks (below) → one D1 transaction:
                                                 consume session, mark user verified, link Telegram id,
                                                 audit row, channel outbox row
App polls GET /api/verification/telegram/:id (auth, owner only, every 3 s while visible) → "verified"
```

## Checks (all server-side, all covered by `backend/test/telegram.test.ts`)
| Attack / error | Result |
|---|---|
| Webhook call without the `X-Telegram-Bot-Api-Secret-Token` header value | 403 |
| Same `update_id` delivered twice | ignored (`telegram_updates` PK) |
| Unknown / malformed token | "invalid link", audited |
| Expired token (at /start or at contact time) | rejected, status `expired` |
| Token reused after success | "already processed"; no second link, no second channel post |
| Token opened by a second Telegram account | refused; that account can't complete it |
| Manually typed number | never accepted; user is shown the button again; audited |
| Contact without `user_id`, or `user_id ≠ sender` (someone else's contact) | rejected (`foreign_contact`) |
| Forwarded contact | rejected (`forwarded_contact`) |
| Number ≠ registered number (after E.164 normalisation) | "الرقم لا يطابق الرقم المسجل…", 3 attempts then `failed` |
| Telegram account already linked to another app account | rejected (checked + UNIQUE constraint rolls back the transaction) |
| Another user reading your verification status | 404 (IDOR protection) |

Audit rows never contain full phone numbers or tokens (`maskPhone`). The admin channel message
contains: internal user id, status, method, date (Africa/Algiers) and verified-user total.

**What this proves:** the person controls a Telegram account whose number equals the registered
number. It is not proof of legal identity or SIM ownership, and it is not immune to fraud
(e.g. a Telegram account registered with someone else's SIM).
