# Running costs (Cloudflare free tier)

| Service | Free allowance (at time of writing — verify on cloudflare.com) | Usage pattern here |
|---|---|---|
| Workers | 100 000 requests/day, 10 ms CPU/request | Home screen = 1 request (aggregated); public responses cacheable 60–300 s; app HTTP cache |
| D1 | 5 GB storage, 5 M rows read/day, 100 k rows written/day | Writes only on user actions + rate-limit counters on sensitive endpoints |
| Pages (dashboard + website) | Unlimited static requests, 500 builds/month | Static |
| Cron trigger | Included | Every 10 min: channel retries, expiry, cleanup |
| R2 (optional) | 10 GB storage, 1 M writes, 10 M reads/month, **no egress fees** | Only if uploads are enabled; prefer YouTube links for video |
| Telegram Bot API | Free | Verification + channel |
| Firebase Cloud Messaging | Free | Optional |
| GitHub Actions | Free for public repos; 2 000 min/month for private | ~10 min per Android build |
| Resend (optional e-mail) | Free tier (limited e-mails/month) | Only verification/reset e-mails |

Possible charges: exceeding Workers/D1 daily limits (requests fail rather than bill on the free
plan), the Workers Paid plan (~5 USD/month) if traffic grows, R2 beyond the free storage, a custom
domain registration. No paid service is enabled by this code.
