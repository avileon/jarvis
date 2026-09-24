import pg from 'pg';
import { config } from '../config.js';

let pool: pg.Pool | null = null;

export function db(): pg.Pool {
  if (!pool) {
    pool = new pg.Pool({ connectionString: config().DATABASE_URL, max: 10 });
    pool.on('error', (e) => console.error('pg pool error', e));
  }
  return pool;
}

export async function q<T extends pg.QueryResultRow = any>(text: string, params: unknown[] = []): Promise<T[]> {
  const r = await db().query<T>(text, params as any[]);
  return r.rows;
}

export async function q1<T extends pg.QueryResultRow = any>(text: string, params: unknown[] = []): Promise<T | null> {
  const rows = await q<T>(text, params);
  return rows[0] ?? null;
}

export async function closeDb() {
  await pool?.end();
  pool = null;
}

const MIGRATIONS: string[] = [
  /* 001 */ `
  CREATE TABLE IF NOT EXISTS admins (
    id SERIAL PRIMARY KEY,
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  CREATE TABLE IF NOT EXISTS admin_sessions (
    token_hash TEXT PRIMARY KEY,
    admin_id INT NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip TEXT
  );
  CREATE TABLE IF NOT EXISTS devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    token_hash TEXT UNIQUE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ,
    last_ip TEXT,
    info JSONB NOT NULL DEFAULT '{}',
    revoked BOOLEAN NOT NULL DEFAULT false
  );
  CREATE TABLE IF NOT EXISTS pairing_codes (
    code_hash TEXT PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INT NOT NULL DEFAULT 0
  );
  CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value JSONB,
    secret_enc TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  CREATE TABLE IF NOT EXISTS conversations (
    id BIGSERIAL PRIMARY KEY,
    device_id UUID REFERENCES devices(id) ON DELETE SET NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_at TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  CREATE TABLE IF NOT EXISTS messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    content JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  CREATE INDEX IF NOT EXISTS messages_conv_idx ON messages(conversation_id, id);
  CREATE TABLE IF NOT EXISTS usage (
    id BIGSERIAL PRIMARY KEY,
    ts TIMESTAMPTZ NOT NULL DEFAULT now(),
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    kind TEXT NOT NULL,
    input_units NUMERIC NOT NULL DEFAULT 0,
    output_units NUMERIC NOT NULL DEFAULT 0,
    cost_usd NUMERIC(12,6) NOT NULL DEFAULT 0,
    meta JSONB NOT NULL DEFAULT '{}'
  );
  CREATE INDEX IF NOT EXISTS usage_ts_idx ON usage(ts);
  CREATE TABLE IF NOT EXISTS action_log (
    id BIGSERIAL PRIMARY KEY,
    ts TIMESTAMPTZ NOT NULL DEFAULT now(),
    source TEXT NOT NULL,
    type TEXT NOT NULL,
    summary TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ok',
    detail JSONB NOT NULL DEFAULT '{}'
  );
  CREATE INDEX IF NOT EXISTS action_log_ts_idx ON action_log(ts DESC);
  CREATE TABLE IF NOT EXISTS pending_actions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    device_id UUID,
    conversation_id BIGINT,
    tool TEXT NOT NULL,
    args JSONB NOT NULL,
    summary TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending'
  );
  CREATE TABLE IF NOT EXISTS errors (
    id BIGSERIAL PRIMARY KEY,
    ts TIMESTAMPTZ NOT NULL DEFAULT now(),
    source TEXT NOT NULL,
    message TEXT NOT NULL,
    detail JSONB NOT NULL DEFAULT '{}'
  );
  CREATE INDEX IF NOT EXISTS errors_ts_idx ON errors(ts DESC);
  CREATE TABLE IF NOT EXISTS smart_devices (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    aliases TEXT[] NOT NULL DEFAULT '{}',
    room TEXT,
    adapter TEXT NOT NULL,
    config_enc TEXT NOT NULL,
    actions JSONB NOT NULL DEFAULT '[]',
    sensitive BOOLEAN NOT NULL DEFAULT false,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  CREATE TABLE IF NOT EXISTS photos (
    id SERIAL PRIMARY KEY,
    drive_id TEXT UNIQUE NOT NULL,
    name TEXT NOT NULL,
    md5 TEXT,
    mime TEXT,
    modified_time TIMESTAMPTZ,
    file_name TEXT,
    bytes INT,
    synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    active BOOLEAN NOT NULL DEFAULT true
  );
  `,
];

export async function migrate() {
  const client = await db().connect();
  try {
    await client.query('CREATE TABLE IF NOT EXISTS _migrations (n INT PRIMARY KEY, at TIMESTAMPTZ NOT NULL DEFAULT now())');
    await client.query('SELECT pg_advisory_lock(424242)');
    const done = new Set((await client.query('SELECT n FROM _migrations')).rows.map((r) => r.n));
    for (let i = 0; i < MIGRATIONS.length; i++) {
      if (done.has(i + 1)) continue;
      await client.query('BEGIN');
      await client.query(MIGRATIONS[i]!);
      await client.query('INSERT INTO _migrations(n) VALUES ($1)', [i + 1]);
      await client.query('COMMIT');
    }
  } catch (e) {
    await client.query('ROLLBACK').catch(() => {});
    throw e;
  } finally {
    await client.query('SELECT pg_advisory_unlock(424242)').catch(() => {});
    client.release();
  }
}
