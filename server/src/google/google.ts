import fs from 'node:fs/promises';
import path from 'node:path';
import sharp from 'sharp';
import { config } from '../config.js';
import { hmac, randomToken, safeEqual } from '../lib/crypto.js';
import { q, q1 } from '../lib/db.js';
import { getSecret, getSettings, setSecret, setSettings } from '../lib/settings.js';
import { logError } from '../lib/log.js';
import { broadcastDevices } from '../lib/hub.js';

const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const API = 'https://www.googleapis.com';

interface Tokens {
  access_token: string;
  refresh_token?: string;
  expires_at: number;
  scope?: string;
}

export function redirectUri() {
  return `${config().PUBLIC_URL.replace(/\/$/, '')}/api/google/callback`;
}

export async function authUrl(): Promise<{ url: string; state: string }> {
  const g = await getSettings('google');
  if (!g.clientId) throw new Error('הגדר Google Client ID תחילה');
  const nonce = randomToken(16);
  const state = `${nonce}.${hmac(nonce)}`;
  const params = new URLSearchParams({
    client_id: g.clientId,
    redirect_uri: redirectUri(),
    response_type: 'code',
    scope: g.scopes.join(' '),
    access_type: 'offline',
    prompt: 'consent',
    include_granted_scopes: 'true',
    state,
  });
  return { url: `https://accounts.google.com/o/oauth2/v2/auth?${params}`, state };
}

export function verifyState(state: string, cookieState: string | undefined) {
  const [nonce, sig] = state.split('.');
  return !!nonce && !!sig && !!cookieState && safeEqual(state, cookieState) && safeEqual(sig, hmac(nonce));
}

export async function exchangeCode(code: string) {
  const g = await getSettings('google');
  const secret = await getSecret('google_client_secret');
  if (!secret) throw new Error('חסר Google Client Secret');
  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ code, client_id: g.clientId, client_secret: secret, redirect_uri: redirectUri(), grant_type: 'authorization_code' }),
  });
  const data: any = await res.json();
  if (!res.ok) throw new Error(`Google token exchange: ${data.error_description ?? data.error}`);
  const tokens: Tokens = { access_token: data.access_token, refresh_token: data.refresh_token, expires_at: Date.now() + (data.expires_in - 60) * 1000, scope: data.scope };
  await setSecret('google_tokens', JSON.stringify(tokens));
  let email = '';
  if (data.id_token) {
    try {
      email = JSON.parse(Buffer.from(data.id_token.split('.')[1], 'base64url').toString()).email ?? '';
    } catch {}
  }
  await setSettings('google', { connectedEmail: email });
  return email;
}

export async function disconnect() {
  const raw = await getSecret('google_tokens');
  if (raw) {
    const t: Tokens = JSON.parse(raw);
    await fetch(`https://oauth2.googleapis.com/revoke?token=${encodeURIComponent(t.refresh_token ?? t.access_token)}`, { method: 'POST' }).catch(() => {});
  }
  await setSecret('google_tokens', null);
  await setSettings('google', { connectedEmail: '' });
}

export async function isConnected() {
  return !!(await getSecret('google_tokens'));
}

async function accessToken(): Promise<string> {
  const raw = await getSecret('google_tokens');
  if (!raw) throw new Error('Google לא מחובר');
  const t: Tokens = JSON.parse(raw);
  if (t.expires_at > Date.now()) return t.access_token;
  if (!t.refresh_token) throw new Error('פג תוקף החיבור ל-Google — יש להתחבר מחדש');
  const g = await getSettings('google');
  const secret = await getSecret('google_client_secret');
  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: g.clientId, client_secret: secret ?? '', refresh_token: t.refresh_token, grant_type: 'refresh_token' }),
  });
  const data: any = await res.json();
  if (!res.ok) throw new Error(`Google refresh: ${data.error_description ?? data.error}`);
  const next: Tokens = { ...t, access_token: data.access_token, expires_at: Date.now() + (data.expires_in - 60) * 1000 };
  await setSecret('google_tokens', JSON.stringify(next));
  return next.access_token;
}

async function gfetch(url: string, init: RequestInit = {}): Promise<Response> {
  const token = await accessToken();
  const res = await fetch(url.startsWith('http') ? url : `${API}${url}`, {
    ...init,
    headers: { ...(init.headers ?? {}), authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(30_000),
  });
  if (!res.ok) throw new Error(`Google API ${res.status}: ${(await res.text()).slice(0, 200)}`);
  return res;
}

// ---------- Calendar ----------
export async function listEvents(opts: { timeMin: string; timeMax: string; query?: string; max?: number }) {
  const p = new URLSearchParams({ timeMin: opts.timeMin, timeMax: opts.timeMax, singleEvents: 'true', orderBy: 'startTime', maxResults: String(opts.max ?? 20), timeZone: 'Asia/Jerusalem' });
  if (opts.query) p.set('q', opts.query);
  const data: any = await (await gfetch(`/calendar/v3/calendars/primary/events?${p}`)).json();
  return (data.items ?? []).map((e: any) => ({
    id: e.id,
    title: e.summary ?? '(ללא כותרת)',
    start: e.start?.dateTime ?? e.start?.date,
    end: e.end?.dateTime ?? e.end?.date,
    allDay: !!e.start?.date,
    location: e.location,
    attendees: (e.attendees ?? []).slice(0, 8).map((a: any) => a.displayName ?? a.email),
    description: e.description?.slice(0, 300),
  }));
}

export async function createEvent(ev: { title: string; start: string; end: string; description?: string; location?: string }) {
  const body = {
    summary: ev.title,
    description: ev.description,
    location: ev.location,
    start: { dateTime: ev.start, timeZone: 'Asia/Jerusalem' },
    end: { dateTime: ev.end, timeZone: 'Asia/Jerusalem' },
  };
  const data: any = await (await gfetch('/calendar/v3/calendars/primary/events', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })).json();
  return { id: data.id, link: data.htmlLink };
}

// ---------- Gmail ----------
function header(msg: any, name: string) {
  return msg.payload?.headers?.find((h: any) => h.name.toLowerCase() === name.toLowerCase())?.value ?? '';
}

export async function searchMail(query: string, max = 8) {
  const p = new URLSearchParams({ q: query || 'in:inbox', maxResults: String(Math.min(max, 15)) });
  const list: any = await (await gfetch(`/gmail/v1/users/me/messages?${p}`)).json();
  const out = [];
  for (const m of list.messages ?? []) {
    const msg: any = await (await gfetch(`/gmail/v1/users/me/messages/${m.id}?format=metadata&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date`)).json();
    out.push({ id: m.id, from: header(msg, 'From'), subject: header(msg, 'Subject'), date: header(msg, 'Date'), snippet: msg.snippet, unread: (msg.labelIds ?? []).includes('UNREAD') });
  }
  return out;
}

function extractText(part: any): string {
  if (!part) return '';
  if (part.mimeType === 'text/plain' && part.body?.data) return Buffer.from(part.body.data, 'base64url').toString('utf8');
  if (part.parts) {
    const plain = part.parts.map(extractText).find((t: string) => t);
    if (plain) return plain;
  }
  if (part.mimeType === 'text/html' && part.body?.data)
    return Buffer.from(part.body.data, 'base64url').toString('utf8').replace(/<style[\s\S]*?<\/style>/gi, '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ');
  return '';
}

export async function readMail(id: string) {
  const msg: any = await (await gfetch(`/gmail/v1/users/me/messages/${encodeURIComponent(id)}?format=full`)).json();
  return { id, from: header(msg, 'From'), subject: header(msg, 'Subject'), date: header(msg, 'Date'), body: extractText(msg.payload).slice(0, 4000) };
}

// ---------- Drive photos ----------
export function parseFolderId(input: string) {
  const m = input.match(/folders\/([a-zA-Z0-9_-]+)/) ?? input.match(/[?&]id=([a-zA-Z0-9_-]+)/);
  return m ? m[1]! : input.trim();
}

export function photosDir() {
  return path.join(config().DATA_DIR, 'photos');
}

let syncing = false;
export async function syncPhotos(): Promise<{ added: number; removed: number; total: number }> {
  if (syncing) return { added: 0, removed: 0, total: -1 };
  syncing = true;
  try {
    const g = await getSettings('google');
    if (!g.photosFolderId) throw new Error('לא הוגדרה תיקיית תמונות');
    await fs.mkdir(photosDir(), { recursive: true });
    const files: any[] = [];
    let pageToken = '';
    do {
      const p = new URLSearchParams({
        q: `'${g.photosFolderId}' in parents and mimeType contains 'image/' and trashed=false`,
        fields: 'nextPageToken, files(id,name,md5Checksum,mimeType,modifiedTime)',
        pageSize: '200',
        supportsAllDrives: 'true',
        includeItemsFromAllDrives: 'true',
      });
      if (pageToken) p.set('pageToken', pageToken);
      const data: any = await (await gfetch(`/drive/v3/files?${p}`)).json();
      files.push(...(data.files ?? []));
      pageToken = data.nextPageToken ?? '';
    } while (pageToken && files.length < 2000);

    const existing = new Map((await q<{ drive_id: string; md5: string; active: boolean }>('SELECT drive_id, md5, active FROM photos')).map((r) => [r.drive_id, r]));
    let added = 0;
    for (const f of files) {
      const ex = existing.get(f.id);
      if (ex && ex.md5 === f.md5Checksum && ex.active) continue;
      try {
        const buf = Buffer.from(await (await gfetch(`/drive/v3/files/${f.id}?alt=media&supportsAllDrives=true`)).arrayBuffer());
        const out = await sharp(buf, { failOn: 'none' }).rotate().resize(1920, 1920, { fit: 'inside', withoutEnlargement: true }).jpeg({ quality: 82, mozjpeg: true }).toBuffer();
        const fileName = `${f.id}.jpg`;
        await fs.writeFile(path.join(photosDir(), fileName), out);
        await q(
          `INSERT INTO photos(drive_id, name, md5, mime, modified_time, file_name, bytes, active, synced_at) VALUES ($1,$2,$3,$4,$5,$6,$7,true,now())
           ON CONFLICT (drive_id) DO UPDATE SET name=EXCLUDED.name, md5=EXCLUDED.md5, modified_time=EXCLUDED.modified_time, file_name=EXCLUDED.file_name, bytes=EXCLUDED.bytes, active=true, synced_at=now()`,
          [f.id, f.name, f.md5Checksum ?? null, f.mimeType, f.modifiedTime, fileName, out.length],
        );
        added++;
      } catch (e) {
        await logError('photos', e, { file: f.name });
      }
    }
    const live = new Set(files.map((f) => f.id));
    let removed = 0;
    for (const [id, r] of existing) {
      if (r.active && !live.has(id)) {
        await q('UPDATE photos SET active=false WHERE drive_id=$1', [id]);
        await fs.rm(path.join(photosDir(), `${id}.jpg`), { force: true });
        removed++;
      }
    }
    if (added || removed) broadcastDevices({ type: 'photos-updated' });
    const total = (await q1<{ n: number }>('SELECT COUNT(*)::int AS n FROM photos WHERE active'))!.n;
    return { added, removed, total };
  } finally {
    syncing = false;
  }
}

export async function listPhotos() {
  return q<{ drive_id: string; name: string; md5: string; bytes: number }>('SELECT drive_id, name, md5, bytes FROM photos WHERE active ORDER BY modified_time DESC NULLS LAST');
}

let timer: NodeJS.Timeout | null = null;
export async function startPhotoScheduler() {
  if (timer) clearInterval(timer);
  const g = await getSettings('google');
  const minutes = Math.max(5, g.syncMinutes || 30);
  timer = setInterval(async () => {
    try {
      if ((await isConnected()) && (await getSettings('google')).photosFolderId) await syncPhotos();
    } catch (e) {
      await logError('photos-sync', e);
    }
  }, minutes * 60_000);
}
