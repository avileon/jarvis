// Copies the onnxruntime-web WASM runtime next to the app so it is served from our own origin (no CDN).
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const dist = path.dirname(require.resolve('onnxruntime-web/wasm'));
const out = new URL('../public/ort/', import.meta.url).pathname;
fs.mkdirSync(out, { recursive: true });
for (const f of ['ort-wasm-simd-threaded.wasm', 'ort-wasm-simd-threaded.mjs']) fs.copyFileSync(path.join(dist, f), path.join(out, f));
console.log('ort runtime copied to public/ort');
