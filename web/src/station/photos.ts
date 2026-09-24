import { api, getToken } from './client';

/**
 * Local photo store on the tablet (Cache Storage). Photos are synced from the server's
 * Google Drive mirror and shown from local copies, so the slideshow keeps working offline.
 */
const CACHE = 'jarvis-photos-v1';
const INDEX_KEY = 'jarvis.photoIndex';

export interface PhotoRef {
  id: string;
  v: string;
  url: string;
}

function cacheKey(p: PhotoRef) {
  return `${p.url}?v=${encodeURIComponent(p.v)}`;
}

export function localIndex(): PhotoRef[] {
  try {
    return JSON.parse(localStorage.getItem(INDEX_KEY) ?? '[]');
  } catch {
    return [];
  }
}

function saveIndex(list: PhotoRef[]) {
  try {
    localStorage.setItem(INDEX_KEY, JSON.stringify(list));
  } catch {}
}

const hasCaches = typeof caches !== 'undefined';

/** Downloads new/changed photos and removes deleted ones. Returns the local list. */
export async function syncPhotos(onProgress?: (done: number, total: number) => void): Promise<PhotoRef[]> {
  const remote = await api<PhotoRef[]>('/api/photos');
  if (!hasCaches) {
    saveIndex(remote);
    return remote;
  }
  const cache = await caches.open(CACHE);
  const keep = new Set(remote.map(cacheKey));
  const have = new Set((await cache.keys()).map((r) => new URL(r.url).pathname + new URL(r.url).search));
  const ready: PhotoRef[] = [];
  let done = 0;
  for (const p of remote) {
    const key = cacheKey(p);
    if (!have.has(key)) {
      try {
        const res = await fetch(p.url, { headers: { authorization: `Bearer ${getToken()}` } });
        if (!res.ok) continue;
        await cache.put(key, res);
      } catch {
        continue;
      }
    }
    ready.push(p);
    onProgress?.(++done, remote.length);
  }
  for (const k of have) if (!keep.has(k)) await cache.delete(k);
  saveIndex(ready);
  return ready;
}

/** Object URL for a locally cached photo (caller must revoke). */
export async function photoUrl(p: PhotoRef): Promise<string | null> {
  if (hasCaches) {
    const res = await (await caches.open(CACHE)).match(cacheKey(p));
    if (res) return URL.createObjectURL(await res.blob());
  }
  try {
    const blob = await api<Blob>(p.url);
    return URL.createObjectURL(blob);
  } catch {
    return null;
  }
}
