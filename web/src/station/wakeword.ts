/**
 * openWakeWord streaming pipeline (runs fully on-device; no audio leaves the tablet while waiting).
 *   16 kHz int16-scaled float audio, 1280-sample chunks (80 ms)
 *   → melspectrogram.onnx  (chunk + 480 samples context → 8 mel frames × 32)   spec = spec/10 + 2
 *   → embedding_model.onnx (last 76 mel frames → 96-d embedding)
 *   → hey_jarvis.onnx      (last 16 embeddings → score 0..1)
 * Mirrors openwakeword/utils.py AudioFeatures._streaming_features.
 */
type Ort = typeof import('onnxruntime-web');
type Session = import('onnxruntime-web').InferenceSession;

export const CHUNK = 1280;
const MEL_CONTEXT = 160 * 3;
const MEL_BINS = 32;
const EMB_WINDOW = 76;
const EMB_DIM = 96;
const MODEL_FRAMES = 16;

export class WakeWordDetector {
  private raw: Float32Array = new Float32Array(0);
  private mel: Float32Array[] = [];
  private emb: Float32Array[] = [];
  private pending: Float32Array = new Float32Array(0);
  private busy = false;
  private hits = 0;
  private cooldownUntil = 0;

  private constructor(
    private ort: Ort,
    private melS: Session,
    private embS: Session,
    private wwS: Session,
    public threshold = 0.5,
    public patience = 2,
  ) {
    // Prime the buffers with silence-equivalent embeddings so detection works from the first second.
    for (let i = 0; i < EMB_WINDOW; i++) this.mel.push(new Float32Array(MEL_BINS).fill(1));
  }

  static async create(ort: Ort, models: { mel: ArrayBuffer | Uint8Array; emb: ArrayBuffer | Uint8Array; ww: ArrayBuffer | Uint8Array }, opts: { threshold?: number; patience?: number } = {}) {
    const so = { executionProviders: ['wasm'], graphOptimizationLevel: 'all' as const };
    const toU8 = (b: ArrayBuffer | Uint8Array) => (b instanceof Uint8Array ? b : new Uint8Array(b));
    const [melS, embS, wwS] = await Promise.all([
      ort.InferenceSession.create(toU8(models.mel), so),
      ort.InferenceSession.create(toU8(models.emb), so),
      ort.InferenceSession.create(toU8(models.ww), so),
    ]);
    return new WakeWordDetector(ort, melS, embS, wwS, opts.threshold, opts.patience);
  }

  reset() {
    this.raw = new Float32Array(0);
    this.pending = new Float32Array(0);
    this.emb = [];
    this.hits = 0;
    this.cooldownUntil = Date.now() + 1500;
  }

  /**
   * Feed 16 kHz mono samples in [-1, 1]. Returns the latest score (or null if nothing was processed).
   * `onWake` fires once per detection.
   */
  async push(samples: Float32Array, onWake: (score: number) => void): Promise<number | null> {
    this.pending = concat(this.pending, samples);
    if (this.busy) return null;
    this.busy = true;
    let last: number | null = null;
    try {
      while (this.pending.length >= CHUNK) {
        const chunk = this.pending.subarray(0, CHUNK);
        this.pending = this.pending.slice(CHUNK);
        last = await this.processChunk(chunk);
        if (last !== null && Date.now() > this.cooldownUntil) {
          this.hits = last >= this.threshold ? this.hits + 1 : 0;
          if (this.hits >= this.patience) {
            this.hits = 0;
            this.cooldownUntil = Date.now() + 2000;
            onWake(last);
          }
        }
      }
    } finally {
      this.busy = false;
    }
    return last;
  }

  private async processChunk(chunk: Float32Array): Promise<number | null> {
    const scaled = new Float32Array(chunk.length);
    for (let i = 0; i < chunk.length; i++) scaled[i] = Math.max(-32768, Math.min(32767, chunk[i]! * 32767));
    this.raw = concat(this.raw, scaled);
    if (this.raw.length > CHUNK + MEL_CONTEXT) this.raw = this.raw.slice(this.raw.length - (CHUNK + MEL_CONTEXT));
    if (this.raw.length < CHUNK + MEL_CONTEXT) return null;

    // 1) mel spectrogram
    const melOut = await this.melS.run({ input: new this.ort.Tensor('float32', this.raw, [1, this.raw.length]) });
    const melData = melOut[this.melS.outputNames[0]!]!.data as Float32Array;
    const frames = melData.length / MEL_BINS;
    for (let f = 0; f < frames; f++) {
      const row = new Float32Array(MEL_BINS);
      for (let b = 0; b < MEL_BINS; b++) row[b] = melData[f * MEL_BINS + b]! / 10 + 2;
      this.mel.push(row);
    }
    if (this.mel.length > EMB_WINDOW + 20) this.mel.splice(0, this.mel.length - (EMB_WINDOW + 20));
    if (this.mel.length < EMB_WINDOW) return null;

    // 2) embedding over the last 76 mel frames
    const win = new Float32Array(EMB_WINDOW * MEL_BINS);
    const start = this.mel.length - EMB_WINDOW;
    for (let f = 0; f < EMB_WINDOW; f++) win.set(this.mel[start + f]!, f * MEL_BINS);
    const embOut = await this.embS.run({ [this.embS.inputNames[0]!]: new this.ort.Tensor('float32', win, [1, EMB_WINDOW, MEL_BINS, 1]) });
    this.emb.push(Float32Array.from(embOut[this.embS.outputNames[0]!]!.data as Float32Array));
    if (this.emb.length > MODEL_FRAMES) this.emb.shift();
    if (this.emb.length < MODEL_FRAMES) return null;

    // 3) wake word classifier
    const feats = new Float32Array(MODEL_FRAMES * EMB_DIM);
    this.emb.forEach((e, i) => feats.set(e, i * EMB_DIM));
    const out = await this.wwS.run({ [this.wwS.inputNames[0]!]: new this.ort.Tensor('float32', feats, [1, MODEL_FRAMES, EMB_DIM]) });
    return (out[this.wwS.outputNames[0]!]!.data as Float32Array)[0]!;
  }
}

function concat(a: Float32Array, b: Float32Array) {
  const out = new Float32Array(a.length + b.length);
  out.set(a);
  out.set(b, a.length);
  return out;
}
