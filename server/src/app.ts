import Fastify from 'fastify';
import cookie from '@fastify/cookie';
import multipart from '@fastify/multipart';
import rateLimit from '@fastify/rate-limit';
import fstatic from '@fastify/static';
import websocket from '@fastify/websocket';
import fs from 'node:fs';
import path from 'node:path';
import { ZodError } from 'zod';
import { config } from './config.js';
import { adminRoutes } from './routes/admin.js';
import { deviceRoutes } from './routes/device.js';
import './tools/builtin.js';

export async function buildApp() {
  const cfg = config();
  const app = Fastify({
    logger: { level: cfg.NODE_ENV === 'test' ? 'silent' : 'info', redact: ['req.headers.authorization', 'req.headers.cookie'] },
    trustProxy: cfg.TRUST_PROXY,
    bodyLimit: 1024 * 1024,
  });

  await app.register(cookie);
  await app.register(multipart);
  await app.register(rateLimit, { max: 300, timeWindow: '1 minute' });
  await app.register(websocket, { options: { maxPayload: 64 * 1024 } });

  app.addHook('onSend', async (req, reply, payload) => {
    reply.header('x-content-type-options', 'nosniff');
    reply.header('referrer-policy', 'same-origin');
    reply.header('x-frame-options', 'DENY');
    reply.header('permissions-policy', 'microphone=(self), camera=(), geolocation=()');
    if (!req.url.startsWith('/api') && !req.url.startsWith('/ws')) {
      reply.header(
        'content-security-policy',
        "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; media-src 'self' blob: data: https: http:; connect-src 'self' ws: wss: blob: data:; worker-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
      );
    }
    return payload;
  });

  app.setErrorHandler((err: any, req, reply) => {
    if (err instanceof ZodError) return reply.code(400).send({ error: 'invalid input', issues: err.issues.slice(0, 5) });
    const status = (err as any).statusCode ?? 500;
    if (status >= 500) req.log.error(err);
    reply.code(status).send({ error: status >= 500 ? 'server error' : err.message });
  });

  app.get('/api/health', async () => ({ ok: true, time: new Date().toISOString() }));

  await app.register(deviceRoutes);
  await app.register(adminRoutes);

  const webDist = path.resolve(cfg.WEB_DIST);
  if (fs.existsSync(path.join(webDist, 'index.html'))) {
    await app.register(fstatic, {
      root: webDist,
      wildcard: false,
      cacheControl: false,
      setHeaders(res, filePath) {
        if (filePath.includes(`${path.sep}assets${path.sep}`)) res.setHeader('cache-control', 'public, max-age=31536000, immutable');
        else if (filePath.includes(`${path.sep}wakeword${path.sep}`) || filePath.includes(`${path.sep}ort${path.sep}`)) res.setHeader('cache-control', 'public, max-age=86400');
        else res.setHeader('cache-control', 'no-cache');
      },
    });
    app.setNotFoundHandler((req, reply) => {
      if (req.url.startsWith('/api') || req.url.startsWith('/ws')) return reply.code(404).send({ error: 'not found' });
      reply.header('cache-control', 'no-cache');
      return reply.sendFile('index.html');
    });
  } else {
    app.log.warn(`Web build not found at ${webDist}`);
  }

  return app;
}
