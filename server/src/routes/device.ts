import type { FastifyInstance } from 'fastify';
import fs from 'node:fs';
import path from 'node:path';
import { z } from 'zod';
import { redeemPairingCode, requireDevice, requireDeviceOrAdmin, deviceFromToken } from '../lib/auth.js';
import { getSettings } from '../lib/settings.js';
import { runTurn, resolvePending, newConversation } from '../ai/agent.js';
import { synthesize, synthesizeCached, transcribe, wavDuration } from '../voice/speech.js';
import { LimitError } from '../lib/usage.js';
import { logError } from '../lib/log.js';
import { addDevice, removeDevice, sendToDevice, getDeviceConn } from '../lib/hub.js';
import { q } from '../lib/db.js';
import { listPhotos, photosDir } from '../google/google.js';
import { sha256 } from '../lib/crypto.js';
import { config } from '../config.js';

let buildId = '';
function webBuildId() {
  if (!buildId) {
    try {
      buildId = sha256(fs.readFileSync(path.join(path.resolve(config().WEB_DIST), 'index.html'), 'utf8')).slice(0, 12);
    } catch {
      buildId = 'dev';
    }
  }
  return buildId;
}

export async function stationConfig() {
  const [station, wake, voice] = await Promise.all([getSettings('station'), getSettings('wake'), getSettings('voice')]);
  return { station, wake, voice: { ackPhrase: voice.ackPhrase, followUpSeconds: voice.followUpSeconds }, buildId: webBuildId() };
}

function emitter(deviceId?: string) {
  return (msg: Record<string, unknown>) => {
    if (deviceId) sendToDevice(deviceId, msg);
  };
}

async function speakable(reply: string, wantAudio: boolean) {
  if (!wantAudio || !reply) return null;
  try {
    return (await synthesize(reply)).toString('base64');
  } catch (e) {
    await logError('tts', e);
    return null;
  }
}

function friendlyError(e: unknown) {
  if (e instanceof LimitError) return e.message;
  const m = e instanceof Error ? e.message : String(e);
  if (/חסר|לא הוגדר|לא מחובר/.test(m)) return m;
  return 'משהו השתבש בעיבוד הבקשה. הפרטים נשמרו ביומן השגיאות.';
}

export async function deviceRoutes(app: FastifyInstance) {
  app.post('/api/pair', { config: { rateLimit: { max: 8, timeWindow: '10 minutes' } } }, async (req, reply) => {
    const body = z.object({ code: z.string().min(6).max(6), name: z.string().max(80).default('טאבלט'), info: z.record(z.any()).default({}) }).parse(req.body);
    const r = await redeemPairingCode(body.code, body.name, body.info);
    if (!r) return reply.code(400).send({ error: 'קוד שגוי או שפג תוקפו' });
    return r;
  });

  // Station APK for sideloading on the tablet (placed in DATA_DIR by the deploy step).
  app.get('/download/jarvis-station.apk', async (_req, reply) => {
    const p = path.join(config().DATA_DIR, 'jarvis-station.apk');
    if (!fs.existsSync(p)) return reply.code(404).send({ error: 'APK not uploaded yet' });
    reply.header('content-type', 'application/vnd.android.package-archive').header('content-disposition', 'attachment; filename="jarvis-station.apk"');
    return reply.send(fs.createReadStream(p));
  });

  app.get('/api/station/config', { preHandler: requireDevice }, async () => stationConfig());

  app.get('/api/tts/ack', { preHandler: requireDeviceOrAdmin }, async (_req, reply) => {
    const voice = await getSettings('voice');
    try {
      const { file, id } = await synthesizeCached(voice.ackPhrase);
      reply.header('content-type', 'audio/mpeg').header('etag', id).header('cache-control', 'private, max-age=86400');
      return reply.send(fs.createReadStream(file));
    } catch (e) {
      await logError('tts-ack', e);
      return reply.code(503).send({ error: friendlyError(e) });
    }
  });

  app.post('/api/voice/turn', { preHandler: requireDeviceOrAdmin }, async (req, reply) => {
    const file = await req.file({ limits: { fileSize: 8 * 1024 * 1024 } });
    if (!file) return reply.code(400).send({ error: 'no audio' });
    const audio = await file.toBuffer();
    const emit = emitter(req.deviceId);
    try {
      emit({ type: 'status', state: 'processing' });
      const seconds = wavDuration(audio) || audio.length / 32000;
      const transcript = await transcribe(audio, file.mimetype || 'audio/wav', seconds);
      if (!transcript || transcript.replace(/[\s.,!?]/g, '').length < 2) return { transcript: '', reply: '', audio: null, empty: true };
      const r = await runTurn({ text: transcript, deviceId: req.deviceId, emit });
      return { transcript, reply: r.reply, audio: await speakable(r.reply, true), pendingAction: r.pendingAction ?? null, actions: r.actions };
    } catch (e) {
      await logError('voice-turn', e, { deviceId: req.deviceId });
      const msg = friendlyError(e);
      return reply.code(200).send({ transcript: '', reply: msg, audio: await speakable(msg, true), error: true });
    }
  });

  app.post('/api/chat', { preHandler: requireDeviceOrAdmin }, async (req) => {
    const body = z.object({ text: z.string().min(1).max(2000), speak: z.boolean().default(false) }).parse(req.body);
    const emit = emitter(req.deviceId);
    try {
      emit({ type: 'status', state: 'processing' });
      const r = await runTurn({ text: body.text, deviceId: req.deviceId, emit });
      return { reply: r.reply, audio: await speakable(r.reply, body.speak), pendingAction: r.pendingAction ?? null, actions: r.actions, conversationId: r.conversationId };
    } catch (e) {
      await logError('chat', e, { deviceId: req.deviceId });
      return { reply: friendlyError(e), audio: null, error: true };
    }
  });

  app.post('/api/conversation/new', { preHandler: requireDeviceOrAdmin }, async (req) => ({ conversationId: await newConversation(req.deviceId) }));

  app.post('/api/actions/:id', { preHandler: requireDeviceOrAdmin }, async (req) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
    const { approve, speak } = z.object({ approve: z.boolean(), speak: z.boolean().default(true) }).parse(req.body);
    const r = await resolvePending(id, approve, emitter(req.deviceId), req.deviceId);
    return { ...r, audio: await speakable(r.reply, speak) };
  });

  app.get('/api/photos', { preHandler: requireDevice }, async () => {
    const rows = await listPhotos();
    return rows.map((r) => ({ id: r.drive_id, v: r.md5 ?? '', url: `/api/photos/${r.drive_id}.jpg` }));
  });

  app.get('/api/photos/:file', { preHandler: requireDevice }, async (req, reply) => {
    const { file } = z.object({ file: z.string().regex(/^[a-zA-Z0-9_-]+\.jpg$/) }).parse(req.params);
    const p = path.join(photosDir(), file);
    if (!fs.existsSync(p)) return reply.code(404).send();
    reply.header('content-type', 'image/jpeg').header('cache-control', 'private, max-age=604800, immutable');
    return reply.send(fs.createReadStream(p));
  });

  app.post('/api/device/log', { preHandler: requireDevice, config: { rateLimit: { max: 30, timeWindow: '1 minute' } } }, async (req) => {
    const body = z.object({ level: z.string().max(10).default('error'), message: z.string().max(2000), detail: z.record(z.any()).default({}) }).parse(req.body);
    await logError(`tablet:${req.deviceName}`, body.message, body.detail);
    return { ok: true };
  });

  // Tablet realtime channel. Auth is the first message, so the token never appears in URLs/logs.
  app.get('/ws/device', { websocket: true }, (socket, req) => {
    let deviceId: string | null = null;
    const authTimer = setTimeout(() => socket.close(4401, 'auth timeout'), 10_000);
    socket.on('message', async (raw: Buffer) => {
      let msg: any;
      try {
        msg = JSON.parse(raw.toString());
      } catch {
        return;
      }
      if (!deviceId) {
        if (msg.type !== 'auth') return socket.close(4401, 'auth required');
        const d = await deviceFromToken(String(msg.token ?? ''));
        if (!d) return socket.close(4401, 'bad token');
        clearTimeout(authTimer);
        deviceId = d.id;
        addDevice({ deviceId: d.id, name: d.name, socket, connectedAt: Date.now(), lastPing: Date.now(), state: 'connected', info: msg.info ?? {} });
        await q('UPDATE devices SET last_seen_at=now(), last_ip=$2, info=info || $3::jsonb WHERE id=$1', [d.id, req.ip, JSON.stringify(msg.info ?? {})]);
        socket.send(JSON.stringify({ type: 'config', ...(await stationConfig()) }));
        return;
      }
      const conn = getDeviceConn(deviceId);
      if (!conn) return;
      if (msg.type === 'ping') {
        conn.lastPing = Date.now();
        socket.send(JSON.stringify({ type: 'pong', t: msg.t }));
      } else if (msg.type === 'state') {
        conn.state = String(msg.state).slice(0, 30);
      }
    });
    socket.on('close', async () => {
      clearTimeout(authTimer);
      if (deviceId) {
        removeDevice(deviceId, socket);
        await q('UPDATE devices SET last_seen_at=now() WHERE id=$1', [deviceId]).catch(() => {});
      }
    });
  });
}
