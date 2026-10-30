# Census apps (الإحصاء)

Two extra Android apps built on the existing system. They use the same accounts, the same sign-in
(`POST /api/auth/login`), the same session renewal and encrypted token store (`:core`), and the same
D1 database. There is no separate account or profile.

| App | Module | Application ID | APK |
| --- | --- | --- | --- |
| Statistics admin (إدارة الإحصاء) | `android-app/statsadmin` | `dz.association.stats.admin` | `association-statistics-admin.apk` |
| Statistics member (إحصاء الجمعية) | `android-app/statsmember` | `dz.association.stats.member` | `association-statistics-member.apk` |

Shared code (census API, form, wilayas/communes, charts, QR, sign-in screens, theme) lives in
`android-app/stats`.

## Who can do what (enforced by the server, `backend/src/routes/stats.ts`)

| | Member | Officer | Manager |
| --- | --- | --- | --- |
| Register self, see own card / history | ✓ | ✓ | ✓ |
| Dashboard, meetings list, register people, confirm attendance | | ✓ | ✓ |
| Create / edit / close meetings, correct records, export, audit log, users database, census team | | | ✓ |

- **No e-mail or password in the admin app.**
  - It sends an activation request; an approver (`TELEGRAM_APPROVER_IDS`) accepts it in the Telegram
    bot and chooses *موظف إحصاء* (officer) or *مسؤول إحصاء* (manager).
  - The device then gets a long-lived, revocable session for a census-only account (no password,
    association role `USER`).
  - The server opens census administration **only** to such bot-activated sessions. An e-mail /
    password sign-in never opens it, even for an `ADMIN`.
- **Managers and officers:**
  - An association `ADMIN` / `SUPER_ADMIN` on an activated device counts as a manager.
  - Others get their level from `stats_staff`.
- **Removing someone from the team** (managers, from the census team screen) disconnects their devices
  and closes the census-only account.
- **Users database** (managers only, every view logged): the association's accounts.
  - Contact details, role and verification.
  - Census profile and participation counts.

## Data (migration `0005_statistics.sql`, additive only)

- `stats_meetings`: meetings, with a check-in window and a "late after" time.
- `stats_registrations`: one row per person and meeting, holding:
  - the person: name, wilaya, commune, birth date, marital status, optional gender;
  - the status: registered / present / late / absent / cancelled;
  - the check-in record: time, officer and method (QR, number or manual).
- Duplicate registrations are blocked twice over:
  - **Account:** an account can register only once per meeting (`(meeting, user)`).
  - **Person:** the same person can register only once per meeting. The check matches on a normalised
    name plus birth date, so Arabic spelling variants of the same name are caught.
- Registration numbers are allocated atomically for each meeting.
- `stats_staff`: census officers and managers.
- `member_profiles` gains `wilaya_code`, `commune_id` and `birth_date`. The form is prefilled from the
  same profile the lectures form uses.
- Wilaya and commune data: the 58 wilayas and 1,541 communes.
  - Source: <https://github.com/othmanus/algeria-cities>.
  - Server copy: `backend/src/data/dzGeo.ts`, which validates that each commune belongs to its wilaya.
  - App copy: `android-app/stats/src/main/assets/dz_geo.json`.

## QR codes

The card's code is `AST1.<registration id>.<HMAC>`:

- It holds no personal data.
- It cannot be forged without the server secret.
- Re-registering after a cancellation issues a new code.

The server confirms attendance only from `registered`, so the same person can never be confirmed twice.

## Live updates

While an app is open, it polls `GET /api/stats/sync` every 4 s. This returns a small change counter;
when it changes, the open screens reload. On network errors the interval backs off to 30 s.

Cloudflare WebSockets would need Durable Objects. The existing deployment uses Workers + D1 only, so
polling was chosen as the mechanism that fits it.

## Exports

Managers export the filtered records from the admin app as PDF, Excel (.xlsx) or CSV:

- The files are generated on the phone, from the full list returned by `?all=1`, and shared with the
  share sheet.
- Every export is written to the audit log.

## Tests

- **Backend:** `backend/test/stats.test.ts` (vitest).
- **End-to-end:** `backend/scripts/e2e-stats.mjs`. It runs against `wrangler dev --local` only.
- **Android:**
  - `:stats` unit tests;
  - `:statsadmin` export tests;
  - Robolectric screenshot tests in both apps (output in `build/screenshots`).
