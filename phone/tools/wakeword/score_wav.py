#!/usr/bin/env python3
"""
Score 16 kHz mono WAV files with an openWakeWord model — the same models and pipeline the app runs.
Use it to evaluate a custom Hebrew "ג'ארביס" model before importing it into the app.

  pip install onnxruntime numpy scipy tqdm requests && pip install --no-deps openwakeword
  python3 score_wav.py --model my_jarvis_he.onnx recordings/*.wav

Reports the max score, how many 80 ms chunks crossed the threshold, and how many detections fired
(2 consecutive chunks above threshold, like the app's default "patience").
"""
import argparse, pathlib
import numpy as np
import scipy.io.wavfile as wav
from openwakeword.model import Model

ASSETS = pathlib.Path(__file__).resolve().parents[2] / "wakeword/src/main/assets/wakeword"

p = argparse.ArgumentParser()
p.add_argument("--model", default=str(ASSETS / "hey_jarvis_v0.1.onnx"))
p.add_argument("--threshold", type=float, default=0.5)
p.add_argument("wavs", nargs="+")
a = p.parse_args()

for f in a.wavs:
    m = Model(wakeword_models=[a.model], inference_framework="onnx",
              melspec_model_path=str(ASSETS / "melspectrogram.onnx"),
              embedding_model_path=str(ASSETS / "embedding_model.onnx"))
    sr, x = wav.read(f)
    assert sr == 16000 and x.dtype == np.int16, f"{f}: need 16 kHz int16 mono"
    if x.ndim > 1: x = x[:, 0]
    scores = np.array([list(m.predict(x[i:i + 1280]).values())[0] for i in range(0, len(x) - 1279, 1280)])
    hits = scores >= a.threshold
    fires = int(np.sum(hits[1:] & hits[:-1] & ~np.concatenate([[False], hits[:-2]])))
    print(f"{pathlib.Path(f).name:30s} max={scores.max():.4f} chunks>={a.threshold}={int(hits.sum())} detections~{fires}")
