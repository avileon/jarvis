export class HttpError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export async function adm<T = any>(path: string, init: RequestInit & { json?: unknown } = {}): Promise<T> {
  const headers: Record<string, string> = { 'x-jarvis-csrf': '1', ...(init.headers as any) };
  let body = init.body;
  if (init.json !== undefined) {
    headers['content-type'] = 'application/json';
    body = JSON.stringify(init.json);
  }
  const res = await fetch(path, { ...init, headers, body, credentials: 'same-origin' });
  const ct = res.headers.get('content-type') ?? '';
  const data = ct.includes('json') ? await res.json() : await res.blob();
  if (!res.ok) throw new HttpError(res.status, (data as any)?.error ?? `HTTP ${res.status}`);
  return data as T;
}

export const put = (path: string, json: unknown) => adm(path, { method: 'PUT', json });
export const post = <T = any>(path: string, json: unknown = {}) => adm<T>(path, { method: 'POST', json });
export const del = (path: string) => adm(path, { method: 'DELETE' });

export function fmtDate(s: string | number | null | undefined) {
  if (!s) return '—';
  return new Date(s).toLocaleString('he-IL', { timeZone: 'Asia/Jerusalem', dateStyle: 'short', timeStyle: 'short' });
}

export const usd = (n: number) => `$${(n ?? 0).toFixed(n < 1 ? 4 : 2)}`;
