/**
 * Radio / media output. Inside the Android shell (APK ≥ 0.2) the stream is played natively
 * (Android MediaPlayer, USAGE_MEDIA) so it follows Bluetooth speakers and the system media volume.
 * In a plain browser it falls back to an <audio> element.
 */
interface NativeBridge {
  radioPlay?: (url: string) => void;
  radioStop?: () => void;
  radioDuck?: (duck: boolean) => void;
  volumeStep?: (delta: number) => number;
  volumeGet?: () => number;
}

const native = (): NativeBridge | undefined => (window as any).JarvisNative;

export class Radio {
  private audio = new Audio();
  private level = 0.8;
  private ducked = false;
  playing = false;
  name = '';

  constructor(onError: (msg: string) => void) {
    const retry = () => {
      const src = this.audio.getAttribute('src');
      if (src && this.playing)
        setTimeout(() => {
          if (this.audio.getAttribute('src') === src && this.playing) {
            this.audio.src = src;
            this.audio.play().catch((e) => onError(`radio retry: ${e}`));
          }
        }, 5000);
    };
    this.audio.addEventListener('error', retry);
    this.audio.addEventListener('ended', retry);
  }

  get isNative() {
    return !!native()?.radioPlay;
  }

  play(url: string, name = '') {
    this.playing = true;
    this.name = name;
    if (this.isNative) return native()!.radioPlay!(url);
    this.audio.src = url;
    this.apply();
    this.audio.play().catch(() => {});
  }

  stop() {
    this.playing = false;
    if (this.isNative) return native()!.radioStop!();
    this.audio.pause();
    this.audio.removeAttribute('src');
  }

  /** Lower the music while JARVIS listens/speaks. */
  duck(on: boolean) {
    if (this.ducked === on) return;
    this.ducked = on;
    if (this.isNative) return native()!.radioDuck?.(on);
    this.apply();
  }

  /** Step the media volume up/down. Returns the new level in percent. */
  step(delta: number): number {
    const n = native();
    if (n?.volumeStep) return n.volumeStep(delta);
    this.level = Math.max(0.05, Math.min(1, this.level + delta * 0.1));
    this.apply();
    return Math.round(this.level * 100);
  }

  private apply() {
    this.audio.volume = this.level * (this.ducked ? 0.2 : 1);
  }
}
