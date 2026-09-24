import fs from 'node:fs/promises';
import { config } from './config.js';
import { migrate, closeDb } from './lib/db.js';
import { ensureAdmin } from './lib/auth.js';
import { buildApp } from './app.js';
import { startPhotoScheduler } from './google/google.js';

async function main() {
  const cfg = config();
  await fs.mkdir(cfg.DATA_DIR, { recursive: true });
  for (let i = 0; ; i++) {
    try {
      await migrate();
      break;
    } catch (e) {
      if (i > 20) throw e;
      console.log('Waiting for database…', (e as Error).message);
      await new Promise((r) => setTimeout(r, 2000));
    }
  }
  await ensureAdmin();
  const app = await buildApp();
  await startPhotoScheduler();
  await app.listen({ port: cfg.PORT, host: cfg.HOST });

  const shutdown = async () => {
    await app.close();
    await closeDb();
    process.exit(0);
  };
  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
