/**
 * On-device speech recognition through the Android shell (Google's built-in recognizer).
 * Free: no audio is sent to our server and no STT API is billed.
 */
type Msg = { type: 'ready' | 'start' | 'level' | 'end' | 'error' | 'result' | 'partial'; text?: string; v?: number; code?: number };

const bridge = () => (window as any).JarvisNative as
  | { speechAvailable?: () => boolean; speechListen?: (lang: string) => void; speechCancel?: () => void }
  | undefined;

export function deviceSttAvailable(): boolean {
  try {
    return !!bridge()?.speechListen && !!bridge()?.speechAvailable?.();
  } catch {
    return false;
  }
}

export function deviceListen(opts: { onStart?: () => void; onLevel?: (v: number) => void; onPartial?: (t: string) => void; timeoutMs?: number }) {
  let done = false;
  let resolveFn: (v: { text: string | null; error?: number }) => void = () => {};
  const finish = (text: string | null, error?: number) => {
    if (done) return;
    done = true;
    clearTimeout(timer);
    (window as any).jarvisSpeech = undefined;
    resolveFn({ text, error });
  };
  const promise = new Promise<{ text: string | null; error?: number }>((resolve) => {
    resolveFn = resolve;
  });
  (window as any).jarvisSpeech = (m: Msg) => {
    if (m.type === 'start') opts.onStart?.();
    else if (m.type === 'level') opts.onLevel?.(m.v ?? 0);
    else if (m.type === 'partial' && m.text) opts.onPartial?.(m.text);
    else if (m.type === 'result') finish(m.text?.trim() || null);
    else if (m.type === 'error') finish(null, m.code);
  };
  const timer = setTimeout(() => {
    bridge()?.speechCancel?.();
    finish(null, -1);
  }, opts.timeoutMs ?? 20000);
  bridge()!.speechListen!('he-IL');
  return {
    promise,
    cancel: () => {
      bridge()?.speechCancel?.();
      finish(null, -2);
    },
  };
}
