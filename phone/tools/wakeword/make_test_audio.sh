#!/usr/bin/env bash
# Synthetic test clips (espeak-ng) used to compare the Kotlin port with the Python reference.
# Synthetic speech is NOT a substitute for real recordings of your voice.
set -e
out=${1:-audio}; mkdir -p "$out"
gen() {
  espeak-ng $1 -w /tmp/_tts.wav "$2"
  python3 - "$out/$3" <<'PY'
import sys, numpy as np, scipy.io.wavfile as w, scipy.signal as s
sr, x = w.read('/tmp/_tts.wav'); y = s.resample_poly(x.astype(np.float32), 16000, sr)
sil = np.zeros(32000, np.float32); o = np.concatenate([sil, y, sil, y, sil])
w.write(sys.argv[1], 16000, np.clip(o / np.abs(o).max() * 12000, -32768, 32767).astype(np.int16))
PY
}
gen "-v en-us -s 150" "hey jarvis" en_hey_jarvis.wav
gen "-v en-gb -s 140" "hey jarvis" gb_hey_jarvis.wav
gen "-v en-us -s 150" "hello world, what a day" en_negative.wav
gen "-v he -s 140" "היי ג'ארביס" he_hey_jarvis.wav
gen "-v he -s 140" "ג'ארביס" he_jarvis.wav
gen "-v he -s 140" "תתקשרי לשלי בבקשה" he_negative.wav
