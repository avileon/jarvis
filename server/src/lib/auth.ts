import type { FastifyReply, FastifyRequest } from 'fastify';
import { q, q1 } from './db.js';
import { hashPassword, randomToken, sha256, verifyPassword } from './crypto.js';
import { config } from '../config.js';

export const SESSION_COOKIE = 'jarvis_admin';
const SESSION_TTL_MS = 1000 * 60 * 60 * 12; // 12h

declare module 'fastify' {
  interface FastifyRequest {
    adminId?: number;
    deviceId?: string;
    deviceName?: string;
  }
}

export async function ensureAdmin() {
  const existing = await q1('SELECT id FROM admins LIMIT 1');
  if (existing) return;
  const { ADMIN_USERNAME, ADMIN_PASSWORD } = config();
  if (!ADMIN_PASSWORD || ADMIN_PASSWORD.length < 10) {
    console.warn('No admin exists. Set ADMIN_PASSWORD (min 10 chars) in .env and restart to create one.');
    return;
  }
  await q('INSERT INTO admins(username, password_hash) VALUES ($1,$2)', [ADMIN_USERNAME, hashPassword(ADMIN_PASSWORD)]);
  console.log(`Admin user "${ADMIN_USERNAME}" created.`);
}

export async function login(username: string, password: string, ip: string): Promise<string | null> {
  const row = await q1<{ id: number; password_hash: string }>('SELECT id, password_hash FROM admins WHERE username=$1', [username]);
  // Constant-ish time even when the user doesn't exist.
  const ok = row ? verifyPassword(password, row.password_hash) : (verifyPassword(password, hashPassword('x')), false);
  if (!row || !ok) return null;
  const token = randomToken(32);
  await q('INSERT INTO admin_sessions(token_hash, admin_id, expires_at, ip) VALUES ($1,$2,$3,$4)', [
    sha256(token),
    row.id,
    new Date(Date.now() + SESSION_TTL_MS),
    ip,
  ]);
  await q('DELETE FROM admin_sessions WHERE expires_at < now()');
  return token;
}

export async function logout(token: string) {
  await q('DELETE FROM admin_sessions WHERE token_hash=$1', [sha256(token)]);
}

export async function adminFromToken(token: string | undefined): Promise<number | null> {
  if (!token) return null;
  const row = await q1<{ admin_id: number }>('SELECT admin_id FROM admin_sessions WHERE token_hash=$1 AND expires_at > now()', [sha256(token)]);
  return row?.admin_id ?? null;
}

export async function requireAdmin(req: FastifyRequest, reply: FastifyReply) {
  const id = await adminFromToken(req.cookies[SESSION_COOKIE]);
  if (!id) return reply.code(401).send({ error: 'unauthorized' });
  // CSRF: state-changing requests must carry a custom header (not settable cross-site without CORS).
  if (req.method !== 'GET' && req.headers['x-jarvis-csrf'] !== '1') {
    return reply.code(403).send({ error: 'csrf' });
  }
  req.adminId = id;
}

export async function deviceFromToken(token: string | undefined): Promise<{ id: string; name: string } | null> {
  if (!token) return null;
  return q1<{ id: string; name: string }>('SELECT id, name FROM devices WHERE token_hash=$1 AND NOT revoked', [sha256(token)]);
}

export function bearer(req: FastifyRequest): string | undefined {
  const h = req.headers.authorization;
  if (h?.startsWith('Bearer ')) return h.slice(7);
  return undefined;
}

export async function requireDevice(req: FastifyRequest, reply: FastifyReply) {
  const d = await deviceFromToken(bearer(req));
  if (!d) return reply.code(401).send({ error: 'device_unauthorized' });
  req.deviceId = d.id;
  req.deviceName = d.name;
}

/** Either an admin session (for testing from the admin panel) or a paired device. */
export async function requireDeviceOrAdmin(req: FastifyRequest, reply: FastifyReply) {
  const d = await deviceFromToken(bearer(req));
  if (d) {
    req.deviceId = d.id;
    req.deviceName = d.name;
    return;
  }
  return requireAdmin(req, reply);
}

export async function createPairingCode(): Promise<string> {
  const code = String(Math.floor(100000 + Math.random() * 900000));
  await q('DELETE FROM pairing_codes WHERE expires_at < now()');
  await q('INSERT INTO pairing_codes(code_hash, expires_at) VALUES ($1, $2)', [sha256(code), new Date(Date.now() + 10 * 60_000)]);
  return code;
}

export async function redeemPairingCode(code: string, name: string, info: Record<string, unknown>) {
  const hash = sha256(code.trim());
  const row = await q1('DELETE FROM pairing_codes WHERE code_hash=$1 AND expires_at > now() RETURNING code_hash', [hash]);
  if (!row) return null;
  const token = randomToken(32);
  const dev = await q1<{ id: string }>('INSERT INTO devices(name, token_hash, info) VALUES ($1,$2,$3) RETURNING id', [
    name.slice(0, 80) || 'טאבלט',
    sha256(token),
    JSON.stringify(info),
  ]);
  return { token, deviceId: dev!.id };
}
