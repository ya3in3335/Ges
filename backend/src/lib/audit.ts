import type { Ctx } from '../env';

export function adminAudit(ctx: Ctx, action: string, entityType: string, entityId: string | null, detail?: unknown): D1PreparedStatement {
  return ctx.env.DB.prepare(
    `INSERT INTO admin_audit (actor_id, actor_role, action, entity_type, entity_id, detail, ip, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
  ).bind(
    ctx.user?.id ?? null,
    ctx.user?.role ?? null,
    action,
    entityType,
    entityId,
    detail === undefined ? null : JSON.stringify(detail).slice(0, 2000),
    ctx.ip,
    ctx.now,
  );
}

export function verificationAudit(
  db: D1Database,
  now: number,
  e: { verificationId?: string | null; userId?: string | null; telegramUserId?: number | null; event: string; detail?: string },
): D1PreparedStatement {
  return db
    .prepare(
      `INSERT INTO verification_audit (verification_id, user_id, telegram_user_id, event, detail, created_at)
       VALUES (?, ?, ?, ?, ?, ?)`,
    )
    .bind(e.verificationId ?? null, e.userId ?? null, e.telegramUserId ?? null, e.event, e.detail ?? null, now);
}
