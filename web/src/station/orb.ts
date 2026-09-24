/**
 * Energy orb — based on Avi's prototype (radial "filaments", halo rings, orbiting ellipses),
 * extended with per-state colour/motion and tuned for old tablets:
 *  - background pre-rendered once per resize
 *  - frame rate capped (30 fps low / 60 fps high), DPR capped
 *  - filament count reduced in low quality
 */
export type OrbState = 'idle' | 'wake' | 'listening' | 'processing' | 'speaking' | 'acting' | 'error' | 'offline';

interface Palette {
  hue: number;
  sat: number;
  activity: number;
  spin: number;
}

const PALETTES: Record<OrbState, Palette> = {
  idle: { hue: 196, sat: 100, activity: 0.035, spin: 1 },
  wake: { hue: 190, sat: 100, activity: 0.25, spin: 1.6 },
  listening: { hue: 186, sat: 100, activity: 0.15, spin: 1.3 },
  processing: { hue: 215, sat: 100, activity: 0.12, spin: 4.2 },
  speaking: { hue: 196, sat: 100, activity: 0.25, spin: 1.5 },
  acting: { hue: 34, sat: 100, activity: 0.22, spin: 2.6 },
  error: { hue: 2, sat: 85, activity: 0.08, spin: 0.8 },
  offline: { hue: 205, sat: 20, activity: 0.02, spin: 0.4 },
};

export class Orb {
  private ctx: CanvasRenderingContext2D;
  private bg: HTMLCanvasElement = document.createElement('canvas');
  private w = 0;
  private h = 0;
  private t = 0;
  private raf = 0;
  private lastFrame = 0;
  private cur: Palette = { ...PALETTES.idle };
  state: OrbState = 'idle';
  level = 0;
  private levelSmooth = 0;
  private low: boolean;
  private running = false;

  constructor(private canvas: HTMLCanvasElement, quality: 'auto' | 'low' | 'high' = 'auto', private levelSource?: () => number) {
    this.ctx = canvas.getContext('2d', { alpha: false })!;
    this.low = quality === 'low' || (quality === 'auto' && isWeakDevice());
    this.resize = this.resize.bind(this);
    window.addEventListener('resize', this.resize);
    this.resize();
  }

  setQuality(q: 'auto' | 'low' | 'high') {
    this.low = q === 'low' || (q === 'auto' && isWeakDevice());
    this.resize();
  }

  private resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, this.low ? 1 : 2);
    this.w = window.innerWidth;
    this.h = window.innerHeight;
    this.canvas.width = Math.round(this.w * dpr);
    this.canvas.height = Math.round(this.h * dpr);
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.bg.width = this.canvas.width;
    this.bg.height = this.canvas.height;
    const b = this.bg.getContext('2d')!;
    b.setTransform(dpr, 0, 0, dpr, 0, 0);
    const x = this.w / 2, y = this.h * 0.43;
    const g = b.createRadialGradient(x, y, 0, x, y, Math.max(this.w, this.h) * 0.65);
    g.addColorStop(0, '#072b46');
    g.addColorStop(0.55, '#041322');
    g.addColorStop(1, '#01040a');
    b.fillStyle = g;
    b.fillRect(0, 0, this.w, this.h);
  }

  start() {
    if (this.running) return;
    this.running = true;
    const loop = (now: number) => {
      if (!this.running) return;
      this.raf = requestAnimationFrame(loop);
      const minDt = this.low ? 1000 / 30 : 1000 / 60;
      if (now - this.lastFrame < minDt - 1) return;
      const dt = Math.min(0.05, (now - (this.lastFrame || now)) / 1000);
      this.lastFrame = now;
      this.draw(dt);
    };
    this.raf = requestAnimationFrame(loop);
  }

  stop() {
    this.running = false;
    cancelAnimationFrame(this.raf);
  }

  destroy() {
    this.stop();
    window.removeEventListener('resize', this.resize);
  }

  private draw(dt: number) {
    const target = PALETTES[this.state];
    const k = Math.min(1, dt * 4);
    // Hue lerp along the short way.
    let dh = target.hue - this.cur.hue;
    if (dh > 180) dh -= 360;
    if (dh < -180) dh += 360;
    this.cur.hue += dh * k;
    this.cur.sat += (target.sat - this.cur.sat) * k;
    this.cur.activity += (target.activity - this.cur.activity) * k;
    this.cur.spin += (target.spin - this.cur.spin) * k;
    const src = this.levelSource ? this.levelSource() : this.level;
    this.levelSmooth += (src - this.levelSmooth) * Math.min(1, dt * 12);
    const level = this.levelSmooth;

    this.t += dt * this.cur.spin;
    const t = this.t;
    const { ctx, w, h } = this;
    const x = w / 2, y = h * 0.43;
    const r = Math.min(w * 0.29, h * 0.235, 170);
    const hue = this.cur.hue, sat = this.cur.sat;

    ctx.drawImage(this.bg, 0, 0, w, h);

    let activity = this.cur.activity;
    if (this.state === 'listening') activity += level * 0.8;
    else if (this.state === 'speaking') activity += level * 0.6 + 0.05 * Math.sin(t * 15);
    else if (this.state === 'processing') activity += 0.05 * Math.sin(t * 3);

    // Halo rings
    const rings = this.low ? 3 : 5;
    for (let j = rings; j >= 0; j--) {
      ctx.beginPath();
      ctx.arc(x, y, r * (1 + j * 0.16 + activity * 0.3), 0, Math.PI * 2);
      ctx.strokeStyle = `hsla(${hue},${sat}%,53%,${(0.1 + activity * 0.2) / (j + 1)})`;
      ctx.lineWidth = 2 + j * 2;
      ctx.stroke();
    }

    // Radial filaments
    const n = this.low ? 64 : 110;
    const lvl = this.state === 'listening' || this.state === 'speaking' ? level : 0;
    ctx.lineWidth = 1.5;
    for (let i = 0; i < n; i++) {
      const a = (i * Math.PI * 2) / n;
      const noise = Math.sin(a * 7 + t * 1.5) * 8 + Math.sin(a * 17 - t * 2.3) * 5;
      const rr = r * (0.76 + activity * 0.5) + noise + lvl * 22 * Math.sin(a * 13 + t * 6);
      const ca = Math.cos(a), sa = Math.sin(a);
      ctx.beginPath();
      ctx.moveTo(x + ca * (rr - 13), y + sa * (rr - 13));
      ctx.lineTo(x + ca * (rr + 13 + activity * 22), y + sa * (rr + 13 + activity * 22));
      ctx.strokeStyle = `hsla(${hue - 3 + 15 * Math.sin(a * 3 + t)},${sat}%,${54 + 15 * Math.sin(t + a)}%,.72)`;
      ctx.stroke();
    }

    // Core sphere
    const core = ctx.createRadialGradient(x - r * 0.23, y - r * 0.3, r * 0.02, x, y, r);
    core.addColorStop(0, `hsla(${hue - 6},${sat}%,84%,.96)`);
    core.addColorStop(0.2, `hsla(${hue},${Math.min(100, sat * 0.9)}%,52%,.88)`);
    core.addColorStop(0.56, `hsla(${hue + 12},${sat * 0.93}%,29%,.67)`);
    core.addColorStop(1, `hsla(${hue + 25},100%,17%,.03)`);
    ctx.fillStyle = core;
    ctx.beginPath();
    ctx.arc(x, y, r * (1 + (this.state === 'speaking' ? level * 0.06 : 0)), 0, Math.PI * 2);
    ctx.fill();

    // Orbiting ellipses
    const ellipses = this.low ? 2 : 3;
    for (let e = 0; e < ellipses; e++) {
      ctx.save();
      ctx.translate(x, y);
      ctx.rotate(t * (e % 2 ? -0.32 : 0.24) + e * 1.04);
      ctx.scale(1, 0.38 + e * 0.11);
      ctx.beginPath();
      ctx.arc(0, 0, r * (0.85 + e * 0.09), 0, Math.PI * 2);
      ctx.strokeStyle = `hsla(${hue + 1},100%,65%,${0.56 - e * 0.13})`;
      ctx.lineWidth = 1.3;
      ctx.stroke();
      ctx.restore();
    }

    // Processing: sweeping arc
    if (this.state === 'processing' || this.state === 'acting') {
      ctx.beginPath();
      ctx.arc(x, y, r * 1.28, t * 2, t * 2 + Math.PI * 0.6);
      ctx.strokeStyle = `hsla(${hue},100%,70%,.7)`;
      ctx.lineWidth = 3;
      ctx.stroke();
      ctx.beginPath();
      ctx.arc(x, y, r * 1.36, -t * 1.4, -t * 1.4 + Math.PI * 0.35);
      ctx.strokeStyle = `hsla(${hue},100%,70%,.4)`;
      ctx.lineWidth = 2;
      ctx.stroke();
    }
  }
}

function isWeakDevice() {
  const cores = (navigator as any).hardwareConcurrency ?? 2;
  const mem = (navigator as any).deviceMemory ?? 2;
  const pixels = window.screen.width * window.screen.height * (window.devicePixelRatio || 1) ** 2;
  return cores <= 4 || mem <= 2 || pixels > 2560 * 1600;
}
