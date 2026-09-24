const TOKEN_KEY = 'jarvis.deviceToken';

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}
export function setToken(t: string | null) {
  try {
    if (t) localStorage.setItem(TOKEN_KEY, t);
    else localStorage.removeItem(TOKEN_KEY);
  } catch {}
  // Mirror into the Android shell (survives WebView data clears), if present.
  (window as any).JarvisNative?.saveToken?.(t ?? '');
}

export class AuthError extends Error {}

export async function api<T = any>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getToken();
  const headers: Record<string, string> = { ...(init.headers as any) };
  if (token) headers.authorization = `Bearer ${token}`;
  if (init.body && typeof init.body === 'string') headers['content-type'] = 'application/json';
  const res = await fetch(path, { ...init, headers });
  if (res.status === 401) throw new AuthError('unauthorized');
  if (!res.ok) throw new Error(`${res.status}`);
  const ct = res.headers.get('content-type') ?? '';
  return (ct.includes('json') ? res.json() : res.blob()) as Promise<T>;
}

export async function pair(code: string, name: string) {
  const res = await fetch('/api/pair', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code, name, info: deviceInfo() }),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error ?? 'pairing failed');
  setToken(data.token);
  return data;
}

export function deviceInfo() {
  const native = (window as any).JarvisNative;
  let nativeInfo: Record<string, unknown> = {};
  try {
    nativeInfo = native?.info ? JSON.parse(native.info()) : {};
  } catch {}
  return {
    ua: navigator.userAgent,
    screen: `${screen.width}x${screen.height}@${window.devicePixelRatio}`,
    cores: (navigator as any).hardwareConcurrency,
    memory: (navigator as any).deviceMemory,
    ...nativeInfo,
  };
}

type Handler = (msg: any) => void;

/** Auto-reconnecting WebSocket with heartbeat. */
export class Channel {
  private ws: WebSocket | null = null;
  private retry = 0;
  private timer: any;
  private hb: any;
  private closed = false;
  online = false;

  constructor(private onMessage: Handler, private onStatus: (online: boolean) => void) {}

  connect() {
    this.closed = false;
    const token = getToken();
    if (!token) return;
    const url = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/device`;
    const ws = new WebSocket(url);
    this.ws = ws;
    ws.onopen = () => {
      ws.send(JSON.stringify({ type: 'auth', token, info: deviceInfo() }));
      this.retry = 0;
      this.setOnline(true);
      clearInterval(this.hb);
      this.hb = setInterval(() => this.send({ type: 'ping', t: Date.now() }), 20000);
    };
    ws.onmessage = (e) => {
      try {
        this.onMessage(JSON.parse(e.data));
      } catch {}
    };
    ws.onclose = (e) => {
      clearInterval(this.hb);
      this.setOnline(false);
      if (e.code === 4401) this.onMessage({ type: 'revoked' });
      if (!this.closed && e.code !== 4401) this.schedule();
    };
    ws.onerror = () => ws.close();
  }

  private setOnline(v: boolean) {
    this.online = v;
    this.onStatus(v);
  }

  private schedule() {
    clearTimeout(this.timer);
    const delay = Math.min(30000, 1000 * 2 ** this.retry++) + Math.random() * 500;
    this.timer = setTimeout(() => this.connect(), delay);
  }

  /** Called when the network comes back (online event / native bridge). */
  kick() {
    if (!this.online) {
      clearTimeout(this.timer);
      this.retry = 0;
      this.ws?.close();
      this.connect();
    }
  }

  send(msg: unknown) {
    if (this.ws?.readyState === 1) this.ws.send(JSON.stringify(msg));
  }

  close() {
    this.closed = true;
    clearTimeout(this.timer);
    clearInterval(this.hb);
    this.ws?.close();
  }
}

export function reportError(message: string, detail: Record<string, unknown> = {}) {
  api('/api/device/log', { method: 'POST', body: JSON.stringify({ message: message.slice(0, 1900), detail }) }).catch(() => {});
}
