import { useCallback, useEffect, useRef, useState } from 'react';
import { Orb, type OrbState } from './orb';
import { MicEngine, recordUtterance, encodeWav, Speaker, b64ToBlob } from './audio';
import { api, AuthError, Channel, getToken, pair, reportError, setToken } from './client';
import { localIndex, photoUrl, syncPhotos, type PhotoRef } from './photos';
import type { WakeWordDetector } from './wakeword';
import './station.css';

interface StationConfig {
  station: { slideshowSeconds: number; idleReturnSeconds: number; showClock: boolean; orbQuality: 'auto' | 'low' | 'high' };
  wake: { enabled: boolean; threshold: number; patience: number };
  voice: { ackPhrase: string; followUpSeconds: number };
}

const DEFAULT_CFG: StationConfig = {
  station: { slideshowSeconds: 15, idleReturnSeconds: 60, showClock: true, orbQuality: 'auto' },
  wake: { enabled: true, threshold: 0.5, patience: 2 },
  voice: { ackPhrase: 'כן אבי, אני מקשיב.', followUpSeconds: 8 },
};

const LABELS: Record<OrbState, string> = {
  idle: 'ג׳ארביס מוכן',
  wake: 'כן?',
  listening: 'מקשיב לך…',
  processing: 'חושב…',
  speaking: 'ג׳ארביס מדבר',
  acting: 'מבצע פעולה…',
  error: 'שגיאה',
  offline: 'אין חיבור לשרת',
};

export default function Station() {
  const [token, setTok] = useState(getToken());
  if (!token) return <Pairing onPaired={() => setTok(getToken())} />;
  return <Jarvis onUnpaired={() => { setToken(null); setTok(null); }} />;
}

function Pairing({ onPaired }: { onPaired: () => void }) {
  const [code, setCode] = useState('');
  const [name, setName] = useState('טאבלט סלון');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  return (
    <div className="pair">
      <header className="brand">J A R V I S<small>צימוד תחנה</small></header>
      <p>בממשק הניהול ← טאבלטים ← "צור קוד צימוד", והזן כאן את הקוד בן 6 הספרות.</p>
      <input inputMode="numeric" maxLength={6} placeholder="קוד צימוד" value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} />
      <input placeholder="שם התחנה" value={name} onChange={(e) => setName(e.target.value)} />
      <button
        disabled={code.length !== 6 || busy}
        onClick={async () => {
          setBusy(true);
          setErr('');
          try {
            await pair(code, name);
            onPaired();
          } catch (e: any) {
            setErr(e.message);
          } finally {
            setBusy(false);
          }
        }}
      >
        צמד תחנה
      </button>
      {err && <div className="err">{err}</div>}
    </div>
  );
}

function Jarvis({ onUnpaired }: { onUnpaired: () => void }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const orbRef = useRef<Orb | null>(null);
  const micRef = useRef(new MicEngine());
  const speakerRef = useRef<Speaker | null>(null);
  const radioRef = useRef<HTMLAudioElement | null>(null);
  const wakeRef = useRef<WakeWordDetector | null>(null);
  const channelRef = useRef<Channel | null>(null);
  const cfgRef = useRef<StationConfig>(DEFAULT_CFG);
  const stateRef = useRef<OrbState>('idle');
  const viewRef = useRef<'slideshow' | 'jarvis'>('jarvis');
  const lastActivity = useRef(Date.now());
  const cancelRec = useRef<(() => void) | null>(null);
  const ackBlob = useRef<Blob | null>(null);
  const sessionId = useRef(0);
  const buildRef = useRef('');

  const [started, setStarted] = useState(false);
  const [view, setViewState] = useState<'slideshow' | 'jarvis'>('jarvis');
  const [orbState, setOrbState] = useState<OrbState>('idle');
  const [status, setStatus] = useState(LABELS.idle);
  const [subtitle, setSubtitle] = useState('אמור "היי ג׳ארביס" או הקש על הכדור.');
  const [online, setOnline] = useState(false);
  const [wakeReady, setWakeReady] = useState<'loading' | 'ready' | 'off' | 'failed'>('loading');
  const [pending, setPendingState] = useState<{ id: string; summary: string } | null>(null);
  const pendingRef = useRef<{ id: string; summary: string } | null>(null);
  const setPending = (p: { id: string; summary: string } | null) => {
    pendingRef.current = p;
    setPendingState(p);
  };
  const [photos, setPhotos] = useState<PhotoRef[]>(localIndex());
  const [cfg, setCfg] = useState<StationConfig>(DEFAULT_CFG);
  const [text, setText] = useState('');

  const setState = useCallback((s: OrbState, label?: string) => {
    stateRef.current = s;
    if (orbRef.current) orbRef.current.state = s;
    setOrbState(s);
    setStatus(label ?? LABELS[s]);
    channelRef.current?.send({ type: 'state', state: s });
    const radio = radioRef.current;
    if (radio) radio.volume = s === 'idle' ? 1 : 0.15;
  }, []);

  const setView = useCallback((v: 'slideshow' | 'jarvis') => {
    viewRef.current = v;
    setViewState(v);
    if (v === 'slideshow') orbRef.current?.stop();
    else orbRef.current?.start();
  }, []);

  const touch = () => (lastActivity.current = Date.now());

  // ---------- conversation flow ----------
  const goIdle = useCallback(() => {
    setState('idle', LABELS.idle);
    touch();
  }, [setState]);

  const playReply = useCallback(
    async (audioB64: string | null | undefined, sid: number) => {
      if (!audioB64 || sid !== sessionId.current) return;
      setState('speaking');
      await speakerRef.current!.play(b64ToBlob(audioB64));
      touch();
    },
    [setState],
  );

  const handleResult = useCallback(
    async (r: any, sid: number) => {
      if (sid !== sessionId.current) return;
      if (r.transcript) setSubtitle(`אתה: ${r.transcript}`);
      if (r.empty) return goIdle();
      if (r.reply) setSubtitle((s) => (r.transcript ? `אתה: ${r.transcript}\nג׳ארביס: ${r.reply}` : `ג׳ארביס: ${r.reply}`));
      setPending(r.pendingAction ?? null);
      await playReply(r.audio, sid);
      if (sid !== sessionId.current) return;
      if (r.error) return goIdle();
      // "ג'ארביס לך לישון" → standby: back to the slideshow, only the wake word wakes it again.
      if (r.sleep) {
        goIdle();
        setSubtitle('');
        setView('slideshow');
        return;
      }
      // Follow-up: keep listening briefly without the wake word (context carries over).
      if (cfgRef.current.voice.followUpSeconds > 0) listen(cfgRef.current.voice.followUpSeconds * 1000, sid);
      else goIdle();
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [goIdle, playReply, setView],
  );

  const sendAudio = useCallback(
    async (samples: Float32Array, sid: number) => {
      setState('processing');
      const form = new FormData();
      form.append('audio', encodeWav(samples), 'speech.wav');
      try {
        const r = await api('/api/voice/turn', { method: 'POST', body: form });
        await handleResult(r, sid);
      } catch (e) {
        if (e instanceof AuthError) return onUnpaired();
        setState('error', 'לא הצלחתי להגיע לשרת');
        reportError(`voice turn failed: ${e}`);
        setTimeout(goIdle, 2500);
      }
    },
    [handleResult, setState, goIdle, onUnpaired],
  );

  const listen = useCallback(
    (startTimeoutMs: number, sid: number) => {
      if (sid !== sessionId.current) return;
      setState('listening');
      const rec = recordUtterance(micRef.current, { startTimeoutMs, onSpeechStart: () => { touch(); setStatus('מקשיב לך…'); } });
      cancelRec.current = rec.cancel;
      rec.promise.then((samples) => {
        cancelRec.current = null;
        if (sid !== sessionId.current) return;
        if (!samples) return goIdle();
        touch();
        sendAudio(samples, sid);
      });
    },
    [setState, goIdle, sendAudio],
  );

  const wake = useCallback(
    async (source: 'voice' | 'tap') => {
      const sid = ++sessionId.current;
      cancelRec.current?.();
      speakerRef.current?.stop();
      touch();
      setView('jarvis');
      setPending(null);
      setSubtitle('');
      setState('wake', 'כן אבי?');
      await micRef.current.resume();
      if (ackBlob.current) await speakerRef.current!.play(ackBlob.current);
      if (sid !== sessionId.current) return;
      wakeRef.current?.reset();
      listen(7000, sid);
      if (source === 'voice') channelRef.current?.send({ type: 'state', state: 'woke' });
    },
    [listen, setState, setView],
  );

  const sendText = useCallback(
    async (t: string) => {
      const q = t.trim();
      if (!q) return;
      const sid = ++sessionId.current;
      cancelRec.current?.();
      setView('jarvis');
      touch();
      setSubtitle(`אתה: ${q}`);
      setState('processing');
      try {
        const r = await api('/api/chat', { method: 'POST', body: JSON.stringify({ text: q, speak: true }) });
        await handleResult({ ...r, transcript: q }, sid);
      } catch (e) {
        if (e instanceof AuthError) return onUnpaired();
        setState('error', 'לא הצלחתי להגיע לשרת');
        setTimeout(goIdle, 2500);
      }
    },
    [handleResult, setState, setView, goIdle, onUnpaired],
  );

  const confirm = useCallback(
    async (approve: boolean) => {
      if (!pending) return;
      const sid = ++sessionId.current;
      cancelRec.current?.();
      const id = pending.id;
      setPending(null);
      setState(approve ? 'acting' : 'processing');
      try {
        const r = await api(`/api/actions/${id}`, { method: 'POST', body: JSON.stringify({ approve }) });
        setSubtitle(`ג׳ארביס: ${r.reply}`);
        await playReply(r.audio, sid);
      } catch {
        /* ignore */
      }
      if (sid === sessionId.current) goIdle();
    },
    [pending, setState, playReply, goIdle],
  );

  // ---------- boot ----------
  useEffect(() => {
    const orb = new Orb(canvasRef.current!, 'auto', () => {
      const s = stateRef.current;
      if (s === 'speaking') return speakerRef.current?.level() ?? 0;
      if (s === 'listening' || s === 'wake') return micRef.current.level;
      return 0;
    });
    orbRef.current = orb;
    orb.start();
    speakerRef.current = new Speaker(() => micRef.current.ctx);
    radioRef.current = new Audio();

    const channel = new Channel(
      (msg) => {
        switch (msg.type) {
          case 'config':
            // New server build deployed → reload the UI (no APK reinstall needed).
            if (msg.buildId && msg.buildId !== 'dev') {
              if (buildRef.current && buildRef.current !== msg.buildId) {
                const tryReload = () => (stateRef.current === 'idle' ? location.reload() : setTimeout(tryReload, 5000));
                tryReload();
              }
              buildRef.current = msg.buildId;
            }
            cfgRef.current = { station: msg.station, wake: msg.wake, voice: msg.voice };
            setCfg(cfgRef.current);
            orb.setQuality(msg.station.orbQuality);
            if (wakeRef.current) {
              wakeRef.current.threshold = msg.wake.threshold;
              wakeRef.current.patience = msg.wake.patience;
            }
            break;
          case 'status':
            if (stateRef.current === 'processing' || stateRef.current === 'acting') setState(msg.state, msg.label);
            break;
          case 'photos-updated':
            syncPhotos().then(setPhotos).catch(() => {});
            break;
          case 'media':
            if (msg.action === 'play' && msg.url) {
              radioRef.current!.src = msg.url;
              radioRef.current!.play().catch((e) => reportError(`radio: ${e}`));
            } else {
              radioRef.current!.pause();
              radioRef.current!.removeAttribute('src');
            }
            break;
          case 'command':
            if (msg.command === 'reload') location.reload();
            if (msg.command === 'slideshow') setView('slideshow');
            if (msg.command === 'wake') wake('tap');
            if (msg.command === 'resync-photos') syncPhotos().then(setPhotos).catch(() => {});
            break;
          case 'revoked':
            onUnpaired();
            break;
        }
      },
      (on) => {
        setOnline(on);
        if (on) syncPhotos().then(setPhotos).catch(() => {});
      },
    );
    channelRef.current = channel;
    channel.connect();

    const onOnline = () => channel.kick();
    window.addEventListener('online', onOnline);
    (window as any).jarvisOnNetwork = (up: boolean) => up && channel.kick();
    const onErr = (e: ErrorEvent) => reportError(`js: ${e.message}`, { src: e.filename, line: e.lineno });
    window.addEventListener('error', onErr);

    // Idle → slideshow
    const idleTimer = setInterval(() => {
      const c = cfgRef.current.station;
      if (viewRef.current === 'jarvis' && stateRef.current === 'idle' && !pendingRef.current && Date.now() - lastActivity.current > c.idleReturnSeconds * 1000) {
        setView('slideshow');
      }
    }, 1000);

    // Auto-start when running inside the Android shell (permissions granted natively).
    if ((window as any).JarvisNative) setTimeout(() => document.getElementById('start-btn')?.click(), 300);

    return () => {
      channel.close();
      orb.destroy();
      clearInterval(idleTimer);
      window.removeEventListener('online', onOnline);
      window.removeEventListener('error', onErr);
      micRef.current.stop();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ---------- start (needs a user gesture in browsers) ----------
  const start = useCallback(async () => {
    setStarted(true);
    try {
      await micRef.current.start();
    } catch (e) {
      setSubtitle('אין גישה למיקרופון. אפשר להקליד הודעה.');
      reportError(`mic: ${e}`);
      setWakeReady('failed');
      return;
    }
    api<Blob>('/api/tts/ack').then((b) => (ackBlob.current = b)).catch(() => {});

    if (!cfgRef.current.wake.enabled) return setWakeReady('off');
    try {
      const ort = await import('onnxruntime-web/wasm');
      ort.env.wasm.wasmPaths = '/ort/';
      ort.env.wasm.numThreads = 1;
      const { WakeWordDetector } = await import('./wakeword');
      const load = (f: string) => fetch(`/wakeword/${f}`).then((r) => r.arrayBuffer());
      const [mel, emb, ww] = await Promise.all([load('melspectrogram.onnx'), load('embedding_model.onnx'), load('hey_jarvis_v0.1.onnx')]);
      const det = await WakeWordDetector.create(ort as any, { mel, emb, ww }, { threshold: cfgRef.current.wake.threshold, patience: cfgRef.current.wake.patience });
      wakeRef.current = det;
      micRef.current.on((frame) => {
        const s = stateRef.current;
        // Wake detection only while waiting; never while we speak (avoids self-triggering).
        if (s !== 'idle' || speakerRef.current?.playing || !cfgRef.current.wake.enabled) return;
        det.push(frame, () => wake('voice'));
      });
      setWakeReady('ready');
    } catch (e) {
      setWakeReady('failed');
      reportError(`wakeword init failed: ${e}`);
      setSubtitle('זיהוי "היי ג׳ארביס" לא זמין במכשיר הזה — הקש על הכדור כדי לדבר.');
    }
  }, [wake]);

  const showStart = !started;

  return (
    <div className="station">
      <canvas ref={canvasRef} className="scene" onClick={() => started && (stateRef.current === 'idle' ? wake('tap') : undefined)} />
      <div className="overlay">
        <header className="brand">
          J A R V I S
          <small>
            {online ? 'מחובר' : 'מתחבר לשרת…'} · {wakeReady === 'ready' ? 'מאזין ל"היי ג׳ארביס"' : wakeReady === 'loading' ? 'טוען זיהוי קולי…' : 'הקש לדיבור'}
          </small>
        </header>
        <main>
          <div className={`status s-${orbState}`}>{status}</div>
          <div className="subtitle">{subtitle}</div>
          {pending && (
            <div className="confirm">
              <div>לאשר: {pending.summary}?</div>
              <button className="yes" onClick={() => confirm(true)}>אשר</button>
              <button onClick={() => confirm(false)}>בטל</button>
            </div>
          )}
        </main>
        <footer>
          <button onClick={() => (stateRef.current === 'listening' ? cancelRec.current?.() : wake('tap'))}>🎙️ {orbState === 'listening' ? 'סיים' : 'דבר'}</button>
          <input
            aria-label="הודעה"
            placeholder="כתוב משהו לג׳ארביס…"
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                sendText(text);
                setText('');
              }
            }}
          />
          <button onClick={() => { sendText(text); setText(''); }}>שלח</button>
          <button onClick={() => setView('slideshow')}>מצב תמונות</button>
        </footer>
      </div>

      {view === 'slideshow' && <Slideshow photos={photos} seconds={cfg.station.slideshowSeconds} showClock={cfg.station.showClock} onTap={() => wake('tap')} online={online} />}

      {showStart && (
        <div className="start" onClick={start}>
          <button id="start-btn">הפעל את ג׳ארביס</button>
          <p>נדרשת הרשאת מיקרופון. ההאזנה ל"היי ג׳ארביס" מתבצעת בטאבלט בלבד — אודיו נשלח לשרת רק אחרי מילת ההפעלה.</p>
        </div>
      )}
    </div>
  );
}

function Slideshow({ photos, seconds, showClock, onTap, online }: { photos: PhotoRef[]; seconds: number; showClock: boolean; onTap: () => void; online: boolean }) {
  const [layers, setLayers] = useState<[string | null, string | null]>([null, null]);
  const [front, setFront] = useState(0);
  const [now, setNow] = useState(new Date());
  const idx = useRef(Math.floor(Math.random() * 1000));

  useEffect(() => {
    let cancelled = false;
    let urls: string[] = [];
    const next = async () => {
      if (!photos.length) return;
      idx.current = (idx.current + 1) % photos.length;
      const url = await photoUrl(photos[idx.current]!);
      if (!url || cancelled) return;
      urls.push(url);
      setFront((f) => {
        const nf = 1 - f;
        setLayers((l) => {
          const copy: [string | null, string | null] = [...l];
          copy[nf] = url;
          return copy;
        });
        return nf;
      });
      // Keep only the two most recent object URLs alive.
      while (urls.length > 2) URL.revokeObjectURL(urls.shift()!);
    };
    next();
    const t = setInterval(next, Math.max(3, seconds) * 1000);
    return () => {
      cancelled = true;
      clearInterval(t);
      urls.forEach((u) => URL.revokeObjectURL(u));
      urls = [];
    };
  }, [photos, seconds]);

  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 15000);
    return () => clearInterval(t);
  }, []);

  return (
    <div className="slideshow" onClick={onTap}>
      {layers.map((src, i) => (src ? <img key={i} src={src} className={i === front ? 'on' : ''} alt="" /> : null))}
      {!photos.length && <div className="empty">✦<p>אין עדיין תמונות. בחר תיקייה ב-Google Drive בממשק הניהול.</p></div>}
      {showClock && (
        <div className="clock">
          <div className="time">{now.toLocaleTimeString('he-IL', { hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Jerusalem' })}</div>
          <div className="date">{now.toLocaleDateString('he-IL', { weekday: 'long', day: 'numeric', month: 'long', timeZone: 'Asia/Jerusalem' })}</div>
        </div>
      )}
      {!online && <div className="offline-dot" title="אין חיבור לשרת" />}
    </div>
  );
}
