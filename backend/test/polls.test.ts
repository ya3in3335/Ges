import { beforeEach, describe, expect, it } from 'vitest';
import { TestApp } from './helpers';

let app: TestApp;
let admin: { token: string; id: string };
beforeEach(async () => {
  app = new TestApp();
  admin = await app.register(99);
  app.setRole(admin.id, 'ADMIN');
});

async function createPoll(over: Record<string, unknown> = {}) {
  const r = await app.req('POST', '/api/admin/polls', {
    token: admin.token,
    body: {
      titleAr: 'انتخاب رئيس الجمعية', startsAt: app.now - 60_000, endsAt: app.now + 86_400_000, status: 'published',
      options: [{ labelAr: 'المرشح أ' }, { labelAr: 'المرشح ب' }], ...over,
    },
  });
  expect(r.status).toBe(201);
  const detail = await app.req('GET', `/api/polls/${r.body.id}`, { token: admin.token });
  return { id: r.body.id as string, options: detail.body.options.map((o: { id: string }) => o.id) as string[] };
}

describe('voting', () => {
  it('requires authentication and a verified phone', async () => {
    const p = await createPoll();
    expect((await app.req('POST', `/api/polls/${p.id}/vote`, { body: { optionId: p.options[0] } })).status).toBe(401);
    const u = await app.register(1);
    const r = await app.req('POST', `/api/polls/${p.id}/vote`, { token: u.token, body: { optionId: p.options[0] } });
    expect(r.status).toBe(403);
    expect(r.body.error).toBe('phone_not_verified');
  });

  it('accepts exactly one vote per eligible account', async () => {
    const p = await createPoll();
    const u = await app.register(1);
    app.markVerified(u.id);
    const first = await app.req('POST', `/api/polls/${p.id}/vote`, { token: u.token, body: { optionId: p.options[0] } });
    expect(first.status).toBe(201);
    expect(first.body.receipt).toMatch(/^[A-Za-z0-9_-]{16}$/);
    const second = await app.req('POST', `/api/polls/${p.id}/vote`, { token: u.token, body: { optionId: p.options[1] } });
    expect(second.status).toBe(409);
    expect(second.body.error).toBe('already_voted');
    const results = await app.req('GET', `/api/admin/polls/${p.id}/results`, { token: admin.token });
    expect(results.body.totalVotes).toBe(1);
    expect(results.body.options.map((o: { votes: number }) => o.votes)).toEqual([1, 0]);
  });

  it('stays consistent under concurrent duplicate submissions', async () => {
    const p = await createPoll();
    const u = await app.register(1);
    app.markVerified(u.id);
    const rs = await Promise.all([0, 1, 0, 1].map((i) => app.req('POST', `/api/polls/${p.id}/vote`, { token: u.token, body: { optionId: p.options[i] } })));
    expect(rs.filter((r) => r.status === 201)).toHaveLength(1);
    expect(app.db.raw.prepare('SELECT COUNT(*) AS n FROM votes WHERE poll_id = ?').get(p.id)).toEqual({ n: 1 });
  });

  it('stores anonymous ballots without a user reference', async () => {
    const p = await createPoll({ anonymous: true });
    const u = await app.register(1);
    app.markVerified(u.id);
    await app.req('POST', `/api/polls/${p.id}/vote`, { token: u.token, body: { optionId: p.options[0] } });
    expect(app.db.raw.prepare('SELECT user_id FROM votes').get()).toEqual({ user_id: null });
  });

  it('makes ballots immutable at the database level', async () => {
    const p = await createPoll({ anonymous: false });
    const u = await app.register(1);
    app.markVerified(u.id);
    await app.req('POST', `/api/polls/${p.id}/vote`, { token: u.token, body: { optionId: p.options[0] } });
    expect(() => app.db.raw.prepare('UPDATE votes SET option_id = ?').run(p.options[1]!)).toThrow(/immutable/);
    expect(() => app.db.raw.prepare(`INSERT INTO poll_options (id, poll_id, label_ar) VALUES ('x', ?, 'c')`).run(p.id)).toThrow(/locked/);
    const edit = await app.req('PATCH', `/api/admin/polls/${p.id}`, { token: admin.token, body: { options: [{ labelAr: 'x' }, { labelAr: 'y' }] } });
    expect(edit.status).toBe(409);
  });

  it('rejects votes outside the voting window and invalid options', async () => {
    const upcoming = await createPoll({ startsAt: app.now + 3600_000, endsAt: app.now + 7200_000 });
    const closed = await createPoll({ startsAt: app.now - 7200_000, endsAt: app.now - 3600_000 });
    const active = await createPoll();
    const u = await app.register(1);
    app.markVerified(u.id);
    expect((await app.req('POST', `/api/polls/${upcoming.id}/vote`, { token: u.token, body: { optionId: upcoming.options[0] } })).body.error).toBe('poll_not_active');
    expect((await app.req('POST', `/api/polls/${closed.id}/vote`, { token: u.token, body: { optionId: closed.options[0] } })).body.error).toBe('poll_not_active');
    expect((await app.req('POST', `/api/polls/${active.id}/vote`, { token: u.token, body: { optionId: closed.options[0] } })).body.error).toBe('invalid_option');
  });

  it('enforces the approved member list for formal elections', async () => {
    const p = await createPoll({ eligibility: 'members' });
    const member = await app.register(1);
    const outsider = await app.register(2);
    app.markVerified(member.id);
    app.markVerified(outsider.id);
    const add = await app.req('POST', `/api/admin/polls/${p.id}/members`, { token: admin.token, body: { identifiers: ['user1@example.com', 'ghost@example.com'] } });
    expect(add.body).toEqual({ added: 1, notFound: ['ghost@example.com'] });
    expect((await app.req('POST', `/api/polls/${p.id}/vote`, { token: outsider.token, body: { optionId: p.options[0] } })).body.error).toBe('not_on_member_list');
    expect((await app.req('POST', `/api/polls/${p.id}/vote`, { token: member.token, body: { optionId: p.options[0] } })).status).toBe(201);
  });

  it('hides results until the poll closes when configured', async () => {
    const p = await createPoll({ resultVisibility: 'after_close', endsAt: app.now + 3600_000 });
    const u = await app.register(1);
    app.markVerified(u.id);
    await app.req('POST', `/api/polls/${p.id}/vote`, { token: u.token, body: { optionId: p.options[0] } });
    const before = await app.req('GET', `/api/polls/${p.id}`, { token: u.token });
    expect(before.body.results).toBeNull();
    expect(before.body.hasVoted).toBe(true);
    app.now += 2 * 3600_000;
    const after = await app.req('GET', `/api/polls/${p.id}`);
    expect(after.body.results.totalVotes).toBe(1);
  });

  it('shows live results when configured and never shows admin-only results to users', async () => {
    const live = await createPoll({ resultVisibility: 'live' });
    const hidden = await createPoll({ resultVisibility: 'admins', startsAt: app.now - 7200_000, endsAt: app.now - 3600_000 });
    const u = await app.register(1);
    expect((await app.req('GET', `/api/polls/${live.id}`, { token: u.token })).body.results).not.toBeNull();
    expect((await app.req('GET', `/api/polls/${hidden.id}`, { token: u.token })).body.results).toBeNull();
  });

  it('does not expose draft polls to users', async () => {
    const p = await createPoll({ status: 'draft' });
    const u = await app.register(1);
    expect((await app.req('GET', `/api/polls/${p.id}`, { token: u.token })).status).toBe(404);
    app.markVerified(u.id);
    expect((await app.req('POST', `/api/polls/${p.id}/vote`, { token: u.token, body: { optionId: p.options[0] } })).status).toBe(404);
  });

  it('only lets ADMIN or above create polls', async () => {
    const editor = await app.register(5);
    app.setRole(editor.id, 'EDITOR');
    const r = await app.req('POST', '/api/admin/polls', { token: editor.token, body: { titleAr: 'xxx', startsAt: 1, endsAt: 2, options: [{ labelAr: 'a' }, { labelAr: 'b' }] } });
    expect(r.status).toBe(403);
  });

  it('records poll creation in the admin audit log', async () => {
    const p = await createPoll();
    const row = app.db.raw.prepare(`SELECT actor_id, action FROM admin_audit WHERE entity_type = 'poll' AND entity_id = ?`).get(p.id);
    expect(row).toEqual({ actor_id: admin.id, action: 'create' });
  });
});
