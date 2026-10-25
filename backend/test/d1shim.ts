/**
 * Minimal Cloudflare D1 API implemented over Node's built-in `node:sqlite`, so the real
 * migrations and SQL run in tests. batch() is executed in a single transaction like D1.
 */
import type { DatabaseSync as DbSync, StatementSync } from 'node:sqlite';
import { createRequire } from 'node:module';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

// Loaded at runtime: Vite does not resolve the `node:sqlite` builtin.
const { DatabaseSync } = createRequire(import.meta.url)('node:sqlite') as typeof import('node:sqlite');

type Bind = null | number | string | bigint | Uint8Array;

class Stmt {
  constructor(private db: ShimD1, public sql: string, public params: Bind[] = []) {}
  bind(...values: unknown[]): Stmt {
    return new Stmt(this.db, this.sql, values.map((v) => (v === undefined ? null : typeof v === 'boolean' ? Number(v) : (v as Bind))));
  }
  private prep(): StatementSync {
    const s = this.db.raw.prepare(this.sql);
    s.setReadBigInts(false);
    return s;
  }
  private args(): Bind[] {
    // D1 supports ?NNN numbered params; node:sqlite binds an array positionally to ?1..?N as well.
    return this.params;
  }
  async first<T>(col?: string): Promise<T | null> {
    const row = this.prep().get(...this.args()) as Record<string, unknown> | undefined;
    if (!row) return null;
    return (col ? row[col] : row) as T;
  }
  async all<T>(): Promise<{ results: T[]; success: true; meta: { changes: number } }> {
    return { results: this.prep().all(...this.args()) as T[], success: true, meta: { changes: 0 } };
  }
  async run() {
    return this.exec();
  }
  exec(): { results: any[]; success: true; meta: { changes: number; last_row_id: number } } {
    const s = this.prep();
    if (/^\s*(SELECT|WITH)/i.test(this.sql) || /\bRETURNING\b/i.test(this.sql)) {
      const rows = s.all(...this.args());
      return { results: rows, success: true, meta: { changes: /^\s*(SELECT|WITH)/i.test(this.sql) ? 0 : rows.length, last_row_id: 0 } };
    }
    const r = s.run(...this.args());
    return { results: [], success: true, meta: { changes: Number(r.changes), last_row_id: Number(r.lastInsertRowid) } };
  }
}

export class ShimD1 {
  raw: DbSync;
  constructor() {
    this.raw = new DatabaseSync(':memory:');
    this.raw.exec('PRAGMA foreign_keys = ON;');
  }
  prepare(sql: string) { return new Stmt(this, sql); }
  async batch(stmts: Stmt[]) {
    this.raw.exec('BEGIN');
    try {
      const out = stmts.map((s) => s.exec());
      this.raw.exec('COMMIT');
      return out;
    } catch (e) {
      this.raw.exec('ROLLBACK');
      throw e;
    }
  }
  async exec(sql: string) { this.raw.exec(sql); return { count: 0, duration: 0 }; }
}

export function migratedDb(): ShimD1 {
  const db = new ShimD1();
  const dir = join(__dirname, '..', 'migrations');
  for (const f of readdirSync(dir).filter((x) => x.endsWith('.sql')).sort()) db.raw.exec(readFileSync(join(dir, f), 'utf8'));
  return db;
}
