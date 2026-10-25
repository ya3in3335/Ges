import type { AuthUser, Ctx, Env, Role } from './env';
import { errorResponse, json } from './lib/http';
import { verifyAccessToken } from './lib/jwt';
import { purgeRateLimits } from './lib/ratelimit';
import { Router } from './router';
import * as auth from './routes/auth';
import * as me from './routes/me';
import * as verification from './routes/verification';
import * as polls from './routes/polls';
import * as news from './routes/news';
import * as activities from './routes/activities';
import * as volunteers from './routes/volunteers';
import * as media from './routes/media';
import * as settings from './routes/settings';
import * as notifications from './routes/notifications';
import * as admin from './routes/admin';
import { home } from './routes/home';
import { flushChannelOutbox } from './services/channel';

export const router = new Router()
  .get('/api/health', () => json({ ok: true }))
  // public content
  .get('/api/home', home)
  .get('/api/settings', settings.getSettings)
  .get('/api/donations', settings.getDonations)
  .get('/api/news', news.listNews)
  .get('/api/news/:id', news.getNews)
  .get('/api/activities', activities.listActivities)
  .get('/api/activities/:id', activities.getActivity)
  .get('/api/media', media.listMedia)
  .get('/media/:key', media.serveMedia)
  .get('/api/polls', polls.listPolls)
  .get('/api/polls/:id', polls.getPoll)
  // auth
  .post('/api/auth/register', auth.register)
  .post('/api/auth/login', auth.login)
  .post('/api/auth/refresh', auth.refresh)
  .post('/api/auth/logout', auth.logout)
  .post('/api/auth/password/forgot', auth.forgotPassword)
  .post('/api/auth/password/reset', auth.resetPassword)
  .post('/api/auth/email/send', auth.sendEmailVerification)
  .post('/api/auth/email/verify', auth.confirmEmailVerification)
  // account
  .get('/api/me', me.getMe)
  .patch('/api/me', me.updateMe)
  .delete('/api/me', me.deleteMe)
  .post('/api/me/password', me.changePassword)
  .get('/api/me/notifications', me.myNotifications)
  .get('/api/me/activities', activities.myActivities)
  .post('/api/devices', me.registerDevice)
  .post('/api/devices/unregister', me.unregisterDevice)
  // telegram verification
  .post('/api/verification/telegram', verification.startTelegramVerification)
  .get('/api/verification/telegram/:id', verification.getTelegramVerification)
  .post('/telegram/webhook', verification.telegramWebhook)
  // participation
  .post('/api/polls/:id/vote', polls.castVote)
  .post('/api/activities/:id/register', activities.registerForActivity)
  .post('/api/activities/:id/cancel', activities.cancelActivityRegistration)
  .post('/api/volunteers', volunteers.applyVolunteer)
  .get('/api/volunteers/me', volunteers.myVolunteer)
  .delete('/api/volunteers/me', volunteers.withdrawVolunteer)
  // administration
  .get('/api/admin/overview', admin.overview)
  .get('/api/admin/telegram-stats', admin.telegramStats)
  .get('/api/admin/users', admin.listUsers)
  .patch('/api/admin/users/:id', admin.updateUser)
  .post('/api/admin/users/:id/reset-verification', admin.resetVerification)
  .get('/api/admin/audit', admin.auditLog)
  .get('/api/admin/news', news.adminListNews)
  .post('/api/admin/news', news.adminCreateNews)
  .patch('/api/admin/news/:id', news.adminUpdateNews)
  .delete('/api/admin/news/:id', news.adminDeleteNews)
  .get('/api/admin/activities', activities.adminListActivities)
  .post('/api/admin/activities', activities.adminCreateActivity)
  .patch('/api/admin/activities/:id', activities.adminUpdateActivity)
  .get('/api/admin/activities/:id/participants', activities.adminActivityParticipants)
  .patch('/api/admin/activities/:id/participants/:userId', activities.adminSetParticipantStatus)
  .get('/api/admin/polls', polls.adminListPolls)
  .post('/api/admin/polls', polls.adminCreatePoll)
  .patch('/api/admin/polls/:id', polls.adminUpdatePoll)
  .get('/api/admin/polls/:id/results', polls.adminPollResults)
  .post('/api/admin/polls/:id/members', polls.adminAddPollMembers)
  .delete('/api/admin/polls/:id/members/:userId', polls.adminRemovePollMember)
  .get('/api/admin/volunteers', volunteers.adminListVolunteers)
  .patch('/api/admin/volunteers/:id', volunteers.adminReviewVolunteer)
  .post('/api/admin/volunteers/:id/assignments', volunteers.adminAssignVolunteer)
  .delete('/api/admin/volunteers/:id/assignments/:activityId', volunteers.adminUnassignVolunteer)
  .get('/api/admin/media', media.adminListMedia)
  .post('/api/admin/media', media.adminAddExternalMedia)
  .post('/api/admin/media/upload', media.adminUploadMedia)
  .patch('/api/admin/media/:id', media.adminUpdateMedia)
  .delete('/api/admin/media/:id', media.adminDeleteMedia)
  .put('/api/admin/donations', settings.adminUpdateDonations)
  .put('/api/admin/settings', settings.adminUpdateSettings)
  .get('/api/admin/notifications', notifications.adminListNotifications)
  .post('/api/admin/notifications', notifications.adminSendNotification);

function corsHeaders(env: Env, origin: string | null): Record<string, string> {
  const allowed = (env.ALLOWED_ORIGINS || '').split(',').map((s) => s.trim()).filter(Boolean);
  if (!origin || !allowed.includes(origin)) return {};
  return {
    'access-control-allow-origin': origin,
    'access-control-allow-methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
    'access-control-allow-headers': 'authorization, content-type',
    'access-control-max-age': '86400',
    vary: 'Origin',
  };
}

/** Resolves the bearer token and checks that its session is still live and the account active. */
async function authenticate(env: Env, req: Request, now: number): Promise<AuthUser | null> {
  const h = req.headers.get('authorization');
  if (!h?.startsWith('Bearer ')) return null;
  const claims = await verifyAccessToken(env.JWT_SECRET, h.slice(7), Math.floor(now / 1000));
  if (!claims) return null;
  const row = await env.DB.prepare(
    `SELECT u.role FROM auth_sessions s JOIN users u ON u.id = s.user_id
     WHERE s.id = ? AND s.user_id = ? AND s.revoked_at IS NULL AND u.status = 'active'`,
  ).bind(claims.sid, claims.sub).first<{ role: Role }>();
  if (!row) return null;
  // Role is always taken from the database, so demotions apply immediately.
  return { id: claims.sub, role: row.role, sessionId: claims.sid };
}

export async function handle(req: Request, env: Env, exec: Pick<ExecutionContext, 'waitUntil'>, f: typeof fetch = fetch, now = Date.now()): Promise<Response> {
  const url = new URL(req.url);
  const cors = corsHeaders(env, req.headers.get('origin'));
  if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors });
  if (!env.JWT_SECRET || env.JWT_SECRET.length < 32) return json({ error: 'server_misconfigured' }, 500);
  let res: Response;
  try {
    const m = router.match(req.method, url.pathname);
    if (m === null) res = json({ error: 'not_found' }, 404);
    else if (m === 'method_not_allowed') res = json({ error: 'method_not_allowed' }, 405);
    else {
      const ctx: Ctx = {
        env, req, url, params: m.params, now, fetch: f,
        ip: req.headers.get('cf-connecting-ip') ?? 'unknown',
        user: url.pathname.startsWith('/telegram/') ? null : await authenticate(env, req, now),
        waitUntil: (p) => exec.waitUntil(p.catch((e) => console.error('background', e))),
      };
      res = await m.handler(ctx);
    }
  } catch (e) {
    res = errorResponse(e);
  }
  if (Object.keys(cors).length) {
    const r = new Response(res.body, res);
    for (const [k, v] of Object.entries(cors)) r.headers.set(k, v);
    return r;
  }
  return res;
}

export default {
  fetch: (req: Request, env: Env, exec: ExecutionContext) => handle(req, env, exec),
  async scheduled(_c: ScheduledController, env: Env, exec: ExecutionContext) {
    const now = Date.now();
    exec.waitUntil((async () => {
      await flushChannelOutbox(env, fetch, now);
      await verification.expireVerifications(env.DB, now);
      await purgeRateLimits(env.DB, now);
    })());
  },
} satisfies ExportedHandler<Env>;
