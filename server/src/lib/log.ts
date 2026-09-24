import { q } from './db.js';
import { broadcastAdmin } from './hub.js';

export async function logError(source: string, err: unknown, detail: Record<string, unknown> = {}) {
  const message = err instanceof Error ? err.message : String(err);
  console.error(`[${source}]`, message);
  try {
    await q('INSERT INTO errors(source, message, detail) VALUES ($1,$2,$3)', [
      source,
      message.slice(0, 2000),
      JSON.stringify({ ...detail, stack: err instanceof Error ? err.stack?.split('\n').slice(0, 6) : undefined }),
    ]);
    broadcastAdmin({ type: 'error', source, message });
  } catch {
    /* db down — console only */
  }
}

export async function logAction(
  source: string,
  type: string,
  summary: string,
  detail: Record<string, unknown> = {},
  status: 'ok' | 'failed' | 'pending' | 'denied' = 'ok',
) {
  try {
    await q('INSERT INTO action_log(source, type, summary, status, detail) VALUES ($1,$2,$3,$4,$5)', [
      source,
      type,
      summary.slice(0, 500),
      status,
      JSON.stringify(detail),
    ]);
    broadcastAdmin({ type: 'action', source, summary, status });
  } catch (e) {
    console.error('logAction failed', e);
  }
}
