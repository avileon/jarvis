import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { adminFromToken, createPairingCode, login, logout, requireAdmin, SESSION_COOKIE } from '../lib/auth.js';
import { hashPassword, sha256, verifyPassword } from '../lib/crypto.js';
import { q, q1 } from '../lib/db.js';
import { DEFAULTS, getAllSettings, getSecret, SECRET_KEYS, secretStatus, setSecret, setSettings, type SettingsKey } from '../lib/settings.js';
import { usageSummary } from '../lib/usage.js';
import { onlineDevices, sendToDevice, addAdmin, broadcastDevices } from '../lib/hub.js';
import { providers, runTurn, newConversation } from '../ai/agent.js';
import { synthesize } from '../voice/speech.js';
import * as google from '../google/google.js';
import { deleteDevice, deviceSchema, executeAction, haDefaultActions, haStates, listDevices, saveDevice } from '../home/home.js';
import { stationConfig } from './device.js';
import { config } from '../config.js';
import { logError } from '../lib/log.js';

export async function adminRoutes(app: FastifyInstance) {
  const secure = config().PUBLIC_URL.startsWith('https');

  app.post('/api/admin/login', { config: { rateLimit: { max: 6, timeWindow: '5 minutes' } } }, async (req, reply) => {
    const { username, password } = z.object({ username: z.string().max(60), password: z.string().max(200) }).parse(req.body);
    const token = await login(username, password, req.ip);
    if (!token) {
      await logError('auth', `כניסה נכשלה עבור "${username}"`, { ip: req.ip });
      return reply.code(401).send({ error: 'שם משתמש או סיסמה שגויים' });
    }
    reply.setCookie(SESSION_COOKIE, token, { httpOnly: true, secure, sameSite: 'strict', path: '/', maxAge: 60 * 60 * 12 });
    return { ok: true };
  });

  app.post('/api/admin/logout', async (req, reply) => {
    const t = req.cookies[SESSION_COOKIE];
    if (t) await logout(t);
    reply.clearCookie(SESSION_COOKIE, { path: '/' });
    return { ok: true };
  });

  app.get('/api/admin/me', async (req, reply) => {
    const id = await adminFromToken(req.cookies[SESSION_COOKIE]);
    if (!id) return reply.code(401).send({ error: 'unauthorized' });
    const row = await q1('SELECT username FROM admins WHERE id=$1', [id]);
    return { username: row?.username };
  });

  app.register(async (r) => {
    r.addHook('preHandler', requireAdmin);

    r.post('/api/admin/password', async (req, reply) => {
      const { current, next } = z.object({ current: z.string(), next: z.string().min(10).max(200) }).parse(req.body);
      const row = await q1<{ password_hash: string }>('SELECT password_hash FROM admins WHERE id=$1', [req.adminId]);
      if (!row || !verifyPassword(current, row.password_hash)) return reply.code(400).send({ error: 'הסיסמה הנוכחית שגויה' });
      await q('UPDATE admins SET password_hash=$1 WHERE id=$2', [hashPassword(next), req.adminId]);
      await q('DELETE FROM admin_sessions WHERE admin_id=$1 AND token_hash <> $2', [req.adminId, sha256(req.cookies[SESSION_COOKIE] ?? '')]);
      return { ok: true };
    });

    // ---- status ----
    r.get('/api/admin/status', async () => {
      const paired = await q('SELECT id, name, created_at, last_seen_at, last_ip, info, revoked FROM devices ORDER BY created_at DESC');
      const errors24 = await q1<{ n: number }>(`SELECT COUNT(*)::int n FROM errors WHERE ts > now() - interval '24 hours'`);
      const photos = await q1<{ n: number }>('SELECT COUNT(*)::int n FROM photos WHERE active');
      const usage = await usageSummary(1);
      const g = (await getAllSettings()).google;
      return {
        online: onlineDevices(),
        paired,
        errors24h: errors24?.n ?? 0,
        photos: photos?.n ?? 0,
        spend: { day: usage.day, month: usage.month },
        google: { connected: await google.isConnected(), email: g.connectedEmail, folder: g.photosFolderId },
        secrets: await secretStatus(),
        publicUrl: config().PUBLIC_URL,
      };
    });

    // ---- settings ----
    r.get('/api/admin/settings', async () => ({ settings: await getAllSettings(), defaults: DEFAULTS, secrets: await secretStatus() }));

    r.put('/api/admin/settings/:key', async (req, reply) => {
      const { key } = z.object({ key: z.enum(Object.keys(DEFAULTS) as [SettingsKey, ...SettingsKey[]]) }).parse(req.params);
      const patch = z.record(z.any()).parse(req.body);
      if (key === 'google' && typeof patch.photosFolderId === 'string') patch.photosFolderId = google.parseFolderId(patch.photosFolderId);
      const next = await setSettings(key, patch as any);
      if (key === 'station' || key === 'wake' || key === 'voice') broadcastDevices({ type: 'config', ...(await stationConfig()) });
      if (key === 'google') await google.startPhotoScheduler();
      return next;
    });

    r.put('/api/admin/secrets/:key', async (req) => {
      const { key } = z.object({ key: z.enum(SECRET_KEYS) }).parse(req.params);
      if (key === 'google_tokens') throw new Error('not editable');
      const { value } = z.object({ value: z.string().max(4000).nullable() }).parse(req.body);
      await setSecret(key, value?.trim() || null);
      return { ok: true, secrets: await secretStatus() };
    });

    r.get('/api/admin/models/:provider', async (req, reply) => {
      const { provider } = z.object({ provider: z.enum(['anthropic', 'openai']) }).parse(req.params);
      const key = await getSecret(provider === 'anthropic' ? 'anthropic_api_key' : 'openai_api_key');
      if (!key) return reply.code(400).send({ error: 'חסר מפתח API' });
      try {
        return { models: await providers[provider]!.listModels(key) };
      } catch (e) {
        return reply.code(502).send({ error: String(e instanceof Error ? e.message : e) });
      }
    });

    // ---- tests ----
    r.post('/api/admin/test/chat', async (req) => {
      const { text, fresh } = z.object({ text: z.string().min(1).max(2000), fresh: z.boolean().default(false) }).parse(req.body);
      const conversationId = fresh ? await newConversation(undefined) : undefined;
      try {
        return await runTurn({ text, conversationId });
      } catch (e) {
        await logError('admin-test-chat', e);
        return { error: e instanceof Error ? e.message : String(e) };
      }
    });

    r.post('/api/admin/test/tts', async (req, reply) => {
      const { text } = z.object({ text: z.string().min(1).max(500) }).parse(req.body);
      try {
        const audio = await synthesize(text);
        return reply.header('content-type', 'audio/mpeg').send(audio);
      } catch (e) {
        return reply.code(502).send({ error: e instanceof Error ? e.message : String(e) });
      }
    });

    // ---- tablets ----
    r.post('/api/admin/devices/pairing-code', async () => ({ code: await createPairingCode(), expiresInMinutes: 10 }));
    r.post('/api/admin/devices/:id/revoke', async (req) => {
      const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
      await q('UPDATE devices SET revoked=true WHERE id=$1', [id]);
      sendToDevice(id, { type: 'revoked' });
      return { ok: true };
    });
    r.post('/api/admin/devices/:id/command', async (req) => {
      const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
      const { command } = z.object({ command: z.enum(['reload', 'slideshow', 'wake', 'resync-photos']) }).parse(req.body);
      return { sent: sendToDevice(id, { type: 'command', command }) };
    });

    // ---- google ----
    r.get('/api/admin/google/auth', async (_req, reply) => {
      const { url, state } = await google.authUrl();
      reply.setCookie('jarvis_gstate', state, { httpOnly: true, secure, sameSite: 'lax', path: '/api/google', maxAge: 600 });
      return { url, redirectUri: google.redirectUri() };
    });
    r.post('/api/admin/google/disconnect', async () => {
      await google.disconnect();
      return { ok: true };
    });
    r.post('/api/admin/photos/sync', async (_req, reply) => {
      try {
        return await google.syncPhotos();
      } catch (e) {
        await logError('photos-sync', e);
        return reply.code(400).send({ error: e instanceof Error ? e.message : String(e) });
      }
    });
    r.get('/api/admin/photos', async () => google.listPhotos());

    // ---- smart home ----
    r.get('/api/admin/home/devices', async () => listDevices(true));
    r.post('/api/admin/home/devices', async (req) => ({ id: await saveDevice(deviceSchema.parse(req.body)) }));
    r.put('/api/admin/home/devices/:id', async (req) => {
      const { id } = z.object({ id: z.coerce.number() }).parse(req.params);
      return { id: await saveDevice(deviceSchema.parse(req.body), id) };
    });
    r.delete('/api/admin/home/devices/:id', async (req) => {
      const { id } = z.object({ id: z.coerce.number() }).parse(req.params);
      await deleteDevice(id);
      return { ok: true };
    });
    r.post('/api/admin/home/devices/:id/test', async (req, reply) => {
      const { id } = z.object({ id: z.coerce.number() }).parse(req.params);
      const { action, value } = z.object({ action: z.string(), value: z.any().optional() }).parse(req.body);
      const d = (await listDevices(true)).find((x) => x.id === id);
      if (!d) return reply.code(404).send({ error: 'not found' });
      try {
        const online = onlineDevices()[0]?.deviceId;
        return { ok: true, result: await executeAction(d, action, value, online) };
      } catch (e) {
        return reply.code(400).send({ error: e instanceof Error ? e.message : String(e) });
      }
    });
    r.get('/api/admin/home/ha-states', async (_req, reply) => {
      try {
        return await haStates();
      } catch (e) {
        return reply.code(400).send({ error: e instanceof Error ? e.message : String(e) });
      }
    });
    r.post('/api/admin/home/ha-import', async (req) => {
      const { entityId, name, room, aliases } = z
        .object({ entityId: z.string(), name: z.string(), room: z.string().optional(), aliases: z.array(z.string()).default([]) })
        .parse(req.body);
      return {
        id: await saveDevice({ name, room, aliases, adapter: 'homeassistant', config: { entityId }, actions: haDefaultActions(entityId), sensitive: entityId.startsWith('lock.'), enabled: true }),
      };
    });

    // ---- history / logs ----
    r.get('/api/admin/conversations', async () =>
      q(`SELECT c.id, c.started_at, c.last_at, d.name AS device,
           (SELECT content->>'content' FROM messages m WHERE m.conversation_id=c.id AND role='user' ORDER BY id LIMIT 1) AS first,
           (SELECT COUNT(*)::int FROM messages m WHERE m.conversation_id=c.id) AS n
         FROM conversations c LEFT JOIN devices d ON d.id=c.device_id ORDER BY c.last_at DESC LIMIT 100`),
    );
    r.get('/api/admin/conversations/:id', async (req) => {
      const { id } = z.object({ id: z.coerce.number() }).parse(req.params);
      return q('SELECT id, role, content, created_at FROM messages WHERE conversation_id=$1 ORDER BY id', [id]);
    });
    r.get('/api/admin/actions', async () => q('SELECT * FROM action_log ORDER BY ts DESC LIMIT 200'));
    r.get('/api/admin/errors', async () => q('SELECT * FROM errors ORDER BY ts DESC LIMIT 200'));
    r.delete('/api/admin/errors', async () => {
      await q('DELETE FROM errors');
      return { ok: true };
    });
    r.get('/api/admin/usage', async (req) => {
      const { days } = z.object({ days: z.coerce.number().min(1).max(365).default(30) }).parse(req.query);
      return usageSummary(days);
    });
  });

  // OAuth callback: needs the admin cookie + signed state.
  app.get('/api/google/callback', async (req, reply) => {
    const admin = await adminFromToken(req.cookies[SESSION_COOKIE]);
    const { code, state, error } = z.object({ code: z.string().optional(), state: z.string().default(''), error: z.string().optional() }).parse(req.query);
    if (!admin) return reply.code(401).send('נדרשת התחברות לממשק הניהול');
    if (error || !code) return reply.redirect('/admin/google?error=' + encodeURIComponent(error ?? 'no_code'));
    if (!google.verifyState(state, req.cookies['jarvis_gstate'])) return reply.code(400).send('state mismatch');
    try {
      await google.exchangeCode(code);
      reply.clearCookie('jarvis_gstate', { path: '/api/google' });
      return reply.redirect('/admin/google?connected=1');
    } catch (e) {
      await logError('google-oauth', e);
      return reply.redirect('/admin/google?error=' + encodeURIComponent(e instanceof Error ? e.message : 'failed'));
    }
  });

  app.get('/ws/admin', { websocket: true }, async (socket, req) => {
    const id = await adminFromToken(req.cookies[SESSION_COOKIE]);
    if (!id) return socket.close(4401, 'unauthorized');
    addAdmin(socket);
  });
}
