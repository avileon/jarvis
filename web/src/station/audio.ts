/**
 * Microphone engine: one getUserMedia stream, resampled to 16 kHz mono and fanned out to
 * the wake-word detector, the utterance recorder and the level meter.
 * Uses ScriptProcessorNode for compatibility with old Android WebViews (no AudioWorklet needed).
 */
export type FrameListener = (samples16k: Float32Array, rms: number) => void;

export class MicEngine {
  ctx: AudioContext | null = null;
  private stream: MediaStream | null = null;
  private proc: ScriptProcessorNode | null = null;
  private listeners = new Set<FrameListener>();
  private carry = 0;
  level = 0;

  async start() {
    if (this.ctx) return;
    const AC: typeof AudioContext = (window as any).AudioContext || (window as any).webkitAudioContext;
    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
    this.ctx = new AC();
    const src = this.ctx.createMediaStreamSource(this.stream);
    this.proc = this.ctx.createScriptProcessor(4096, 1, 1);
    const ratio = this.ctx.sampleRate / 16000;
    this.proc.onaudioprocess = (e) => {
      const input = e.inputBuffer.getChannelData(0);
      // Box-filter downsample to 16 kHz (adequate for speech + wake word, cheap on old CPUs).
      const outLen = Math.floor((input.length - this.carry) / ratio);
      const out = new Float32Array(Math.max(0, outLen));
      let pos = this.carry;
      let sumSq = 0;
      for (let i = 0; i < outLen; i++) {
        const start = Math.floor(pos);
        const end = Math.min(input.length, Math.floor(pos + ratio));
        let acc = 0;
        for (let j = start; j < end; j++) acc += input[j]!;
        const v = acc / Math.max(1, end - start);
        out[i] = v;
        sumSq += v * v;
        pos += ratio;
      }
      this.carry = pos - input.length;
      if (this.carry < 0) this.carry = 0;
      const rms = Math.sqrt(sumSq / Math.max(1, outLen));
      this.level = this.level * 0.6 + Math.min(1, rms * 8) * 0.4;
      for (const l of this.listeners) l(out, rms);
    };
    src.connect(this.proc);
    // Must be connected to run in some engines; output is silent.
    const mute = this.ctx.createGain();
    mute.gain.value = 0;
    this.proc.connect(mute);
    mute.connect(this.ctx.destination);
  }

  on(l: FrameListener) {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }

  async resume() {
    if (this.ctx?.state === 'suspended') await this.ctx.resume();
  }

  stop() {
    this.proc?.disconnect();
    this.stream?.getTracks().forEach((t) => t.stop());
    this.ctx?.close();
    this.ctx = null;
  }
}

/**
 * Energy-based endpointing: waits for speech, records it, stops after trailing silence.
 * Adaptive noise floor so it works in quiet and noisy rooms.
 */
export function recordUtterance(
  mic: MicEngine,
  opts: { startTimeoutMs: number; maxMs?: number; silenceMs?: number; onSpeechStart?: () => void },
): { promise: Promise<Float32Array | null>; cancel: () => void } {
  const maxMs = opts.maxMs ?? 15000;
  const silenceMs = opts.silenceMs ?? 900;
  const chunks: Float32Array[] = [];
  const pre: Float32Array[] = [];
  let floor = 0.004;
  let speaking = false;
  let voicedFrames = 0;
  let silentFor = 0;
  let elapsed = 0;
  let done = false;
  let off: () => void = () => {};
  let resolveFn: (v: Float32Array | null) => void = () => {};

  const finish = (ok: boolean) => {
    if (done) return;
    done = true;
    off();
    if (!ok || !speaking) return resolveFn(null);
    const total = chunks.reduce((n, c) => n + c.length, 0);
    const out = new Float32Array(total);
    let o = 0;
    for (const c of chunks) {
      out.set(c, o);
      o += c.length;
    }
    resolveFn(out);
  };

  const promise = new Promise<Float32Array | null>((resolve) => {
    resolveFn = resolve;
    off = mic.on((frame, rms) => {
      const ms = (frame.length / 16000) * 1000;
      elapsed += ms;
      if (!speaking) {
        pre.push(frame);
        if (pre.length > 6) pre.shift(); // keep ~0.5 s pre-roll
        if (rms < floor * 2) floor = floor * 0.95 + rms * 0.05;
        if (rms > Math.max(0.012, floor * 3.2)) voicedFrames++;
        else voicedFrames = Math.max(0, voicedFrames - 1);
        if (voicedFrames >= 2) {
          speaking = true;
          chunks.push(...pre);
          opts.onSpeechStart?.();
        } else if (elapsed > opts.startTimeoutMs) finish(false);
        return;
      }
      chunks.push(frame);
      if (rms < Math.max(0.009, floor * 2.2)) silentFor += ms;
      else silentFor = 0;
      if (silentFor >= silenceMs || elapsed >= maxMs) finish(true);
    });
  });
  return { promise, cancel: () => finish(false) };
}

export function encodeWav(samples: Float32Array, sampleRate = 16000): Blob {
  const buf = new ArrayBuffer(44 + samples.length * 2);
  const v = new DataView(buf);
  const w = (o: number, s: string) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)));
  w(0, 'RIFF');
  v.setUint32(4, 36 + samples.length * 2, true);
  w(8, 'WAVE');
  w(12, 'fmt ');
  v.setUint32(16, 16, true);
  v.setUint16(20, 1, true);
  v.setUint16(22, 1, true);
  v.setUint32(24, sampleRate, true);
  v.setUint32(28, sampleRate * 2, true);
  v.setUint16(32, 2, true);
  v.setUint16(34, 16, true);
  w(36, 'data');
  v.setUint32(40, samples.length * 2, true);
  for (let i = 0; i < samples.length; i++) v.setInt16(44 + i * 2, Math.max(-1, Math.min(1, samples[i]!)) * 0x7fff, true);
  return new Blob([buf], { type: 'audio/wav' });
}

/** Plays MP3 audio and exposes an output level for the orb. */
export class Speaker {
  private audio = new Audio();
  private analyser: AnalyserNode | null = null;
  private data: Uint8Array | null = null;
  private url = '';

  constructor(private ctx: () => AudioContext | null) {
    this.audio.preload = 'auto';
  }

  private ensureGraph() {
    const ctx = this.ctx();
    if (!ctx || this.analyser) return;
    try {
      const src = ctx.createMediaElementSource(this.audio);
      this.analyser = ctx.createAnalyser();
      this.analyser.fftSize = 256;
      this.data = new Uint8Array(this.analyser.frequencyBinCount);
      src.connect(this.analyser);
      this.analyser.connect(ctx.destination);
    } catch {
      /* analyser optional */
    }
  }

  level(): number {
    if (!this.analyser || !this.data || this.audio.paused) return 0;
    this.analyser.getByteTimeDomainData(this.data as any);
    let sum = 0;
    for (let i = 0; i < this.data.length; i++) {
      const x = (this.data[i]! - 128) / 128;
      sum += x * x;
    }
    return Math.min(1, Math.sqrt(sum / this.data.length) * 4);
  }

  play(src: Blob | string): Promise<void> {
    this.stop();
    this.ensureGraph();
    this.url = typeof src === 'string' ? src : URL.createObjectURL(src);
    this.audio.src = this.url;
    return new Promise((resolve) => {
      const end = () => {
        this.audio.onended = this.audio.onerror = null;
        resolve();
      };
      this.audio.onended = end;
      this.audio.onerror = end;
      this.audio.play().catch(end);
    });
  }

  stop() {
    this.audio.pause();
    if (this.url.startsWith('blob:')) URL.revokeObjectURL(this.url);
    this.url = '';
  }

  get playing() {
    return !this.audio.paused;
  }
}

export function b64ToBlob(b64: string, type = 'audio/mpeg') {
  const bin = atob(b64);
  const arr = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
  return new Blob([arr], { type });
}
