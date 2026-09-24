import fs from 'node:fs/promises';
import path from 'node:path';
import { config } from '../config.js';
import { sha256 } from '../lib/crypto.js';
import { getSecret, getSettings } from '../lib/settings.js';
import { recordStt, recordTts } from '../lib/usage.js';
import { OPENAI_BASE } from '../ai/openai.js';

const AZURE_TTS_URL = (region: string) =>
  process.env.AZURE_TTS_URL ?? `https://${region}.tts.speech.microsoft.com/cognitiveservices/v1`;

/** Transcribe Hebrew speech. `audio` is a WAV/WebM/MP3 buffer. */
export async function transcribe(audio: Buffer, mime: string, durationSec: number): Promise<string> {
  const voice = await getSettings('voice');
  const key = await getSecret('openai_api_key');
  if (!key) throw new Error('חסר מפתח OpenAI לזיהוי דיבור');
  const ext = mime.includes('wav') ? 'wav' : mime.includes('webm') ? 'webm' : mime.includes('ogg') ? 'ogg' : mime.includes('mp4') ? 'mp4' : 'mp3';
  const form = new FormData();
  form.append('file', new Blob([new Uint8Array(audio)], { type: mime }), `speech.${ext}`);
  form.append('model', voice.sttModel);
  form.append('language', 'he');
  form.append('prompt', "שיחה עם העוזר האישי ג'ארביס. המשתמש: אבי.");
  const res = await fetch(`${OPENAI_BASE}/v1/audio/transcriptions`, {
    method: 'POST',
    headers: { authorization: `Bearer ${key}` },
    body: form,
    signal: AbortSignal.timeout(60_000),
  });
  const body = await res.text();
  if (!res.ok) throw new Error(`STT ${res.status}: ${body.slice(0, 200)}`);
  await recordStt(voice.sttModel, durationSec);
  return (JSON.parse(body).text ?? '').trim();
}

function escapeXml(s: string) {
  return s.replace(/[<>&'"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', "'": '&apos;', '"': '&quot;' })[c]!);
}

export function buildSsml(text: string, v: { azureVoice: string; azureRate: string; azurePitch: string; azureStyle: string }) {
  const inner = `<prosody rate="${escapeXml(v.azureRate || '0%')}" pitch="${escapeXml(v.azurePitch || '0%')}">${escapeXml(text)}</prosody>`;
  const styled = v.azureStyle ? `<mstts:express-as style="${escapeXml(v.azureStyle)}">${inner}</mstts:express-as>` : inner;
  return `<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xmlns:mstts="https://www.w3.org/2001/mstts" xml:lang="he-IL"><voice name="${escapeXml(v.azureVoice)}">${styled}</voice></speak>`;
}

async function synthAzure(text: string): Promise<Buffer> {
  const v = await getSettings('voice');
  const key = await getSecret('azure_speech_key');
  if (!key) throw new Error('חסר מפתח Azure Speech');
  const res = await fetch(AZURE_TTS_URL(v.azureRegion), {
    method: 'POST',
    headers: {
      'Ocp-Apim-Subscription-Key': key,
      'Content-Type': 'application/ssml+xml',
      'X-Microsoft-OutputFormat': 'audio-24khz-48kbitrate-mono-mp3',
      'User-Agent': 'jarvis',
    },
    body: buildSsml(text, v),
    signal: AbortSignal.timeout(30_000),
  });
  if (!res.ok) throw new Error(`Azure TTS ${res.status}: ${(await res.text()).slice(0, 200)}`);
  await recordTts('azure', v.azureVoice, text.length);
  return Buffer.from(await res.arrayBuffer());
}

async function synthOpenAi(text: string): Promise<Buffer> {
  const v = await getSettings('voice');
  const key = await getSecret('openai_api_key');
  if (!key) throw new Error('חסר מפתח OpenAI');
  const body: Record<string, unknown> = { model: v.openaiTtsModel, voice: v.openaiVoice, input: text, response_format: 'mp3' };
  if (v.openaiTtsModel.startsWith('gpt-4o'))
    body.instructions = 'Speak Hebrew. Deep, calm, confident male voice of a refined futuristic AI butler. Measured pace, warm and precise.';
  const res = await fetch(`${OPENAI_BASE}/v1/audio/speech`, {
    method: 'POST',
    headers: { authorization: `Bearer ${key}`, 'content-type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(30_000),
  });
  if (!res.ok) throw new Error(`OpenAI TTS ${res.status}: ${(await res.text()).slice(0, 200)}`);
  await recordTts('openai', v.openaiTtsModel, text.length);
  return Buffer.from(await res.arrayBuffer());
}

export async function synthesize(text: string): Promise<Buffer> {
  const v = await getSettings('voice');
  return v.ttsProvider === 'openai' ? synthOpenAi(text) : synthAzure(text);
}

/** Cached synthesis for fixed phrases (e.g. the wake acknowledgement). */
export async function synthesizeCached(text: string): Promise<{ file: string; id: string }> {
  const v = await getSettings('voice');
  const id = sha256(JSON.stringify([text, v.ttsProvider, v.azureVoice, v.azureRate, v.azurePitch, v.azureStyle, v.openaiVoice, v.openaiTtsModel])).slice(0, 24);
  const dir = path.join(config().DATA_DIR, 'tts-cache');
  const file = path.join(dir, `${id}.mp3`);
  try {
    await fs.access(file);
  } catch {
    await fs.mkdir(dir, { recursive: true });
    await fs.writeFile(file, await synthesize(text));
  }
  return { file, id };
}

/** Rough duration of a 16-bit mono PCM WAV. */
export function wavDuration(buf: Buffer): number {
  if (buf.length < 44 || buf.toString('ascii', 0, 4) !== 'RIFF') return 0;
  const byteRate = buf.readUInt32LE(28);
  return byteRate ? (buf.length - 44) / byteRate : 0;
}
