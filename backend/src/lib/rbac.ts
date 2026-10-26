import type { AuthUser, Ctx, Role } from '../env';
import { forbidden, unauthorized } from './http';

export const RANK: Record<Role, number> = { USER: 0, VOLUNTEER: 1, EDITOR: 2, ADMIN: 3, SUPER_ADMIN: 4 };

export const roleAtLeast = (role: Role, min: Role) => RANK[role] >= RANK[min];
export const ROLES = Object.keys(RANK) as Role[];

export function requireUser(ctx: Ctx): AuthUser {
  if (!ctx.user) throw unauthorized();
  return ctx.user;
}

export function requireRole(ctx: Ctx, min: Role): AuthUser {
  const u = requireUser(ctx);
  if (!roleAtLeast(u.role, min)) throw forbidden('insufficient_role');
  return u;
}

/** An actor may only assign roles strictly below their own (SUPER_ADMIN may assign anything). */
export function canAssignRole(actor: Role, target: Role, currentTargetRole: Role): boolean {
  if (actor === 'SUPER_ADMIN') return true;
  return RANK[target] < RANK[actor] && RANK[currentTargetRole] < RANK[actor];
}
