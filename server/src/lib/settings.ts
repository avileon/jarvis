import { q, q1 } from './db.js';
import { decrypt, encrypt } from './crypto.js';

export interface AiSettings {
  provider: 'anthropic' | 'openai';
  model: string;
  temperature: number;
  maxHistoryMessages: number;
  conversationIdleMinutes: number;
  maxToolRounds: number;
  userName: string;
  systemPromptExtra: string;
}
export interface LimitSettings {
  dailyUsd: number;
  monthlyUsd: number;
  requestsPerMinute: number;
}
/** USD. LLM prices are per 1M tokens; STT per minute; TTS per 1M characters. Editable in admin — verify against the providers' price pages. */
export interface PricingSettings {
  llm: Record<string, { in: number; out: number }>;
  stt: Record<string, number>;
  tts: Record<string, number>;
}
export interface VoiceSettings {
  sttModel: string;
  ttsProvider: 'azure' | 'openai';
  azureRegion: string;
  azureVoice: string;
  azureRate: string;
  azurePitch: string;
  azureStyle: string;
  openaiVoice: string;
  openaiTtsModel: string;
  ackPhrase: string;
  followUpSeconds: number;
}
export interface WakeSettings {
  enabled: boolean;
  threshold: number;
  /** Frames above threshold needed to trigger (reduces false positives). */
  patience: number;
}
export interface StationSettings {
  slideshowSeconds: number;
  idleReturnSeconds: number;
  showClock: boolean;
  orbQuality: 'auto' | 'low' | 'high';
}
export interface GoogleSettings {
  clientId: string;
  photosFolderId: string;
  /** Public shared Google Photos album link (preferred source; no OAuth). */
  photosAlbumUrl: string;
  syncMinutes: number;
  connectedEmail: string;
  scopes: string[];
}

export interface HomeSettings {
  haUrl: string;
  mqttUrl: string;
  mqttUsername: string;
}

export interface AllSettings {
  home: HomeSettings;
  ai: AiSettings;
  limits: LimitSettings;
  pricing: PricingSettings;
  voice: VoiceSettings;
  wake: WakeSettings;
  station: StationSettings;
  google: GoogleSettings;
}

export const DEFAULTS: AllSettings = {
  home: { haUrl: '', mqttUrl: '', mqttUsername: '' },
  ai: {
    provider: 'anthropic',
    model: 'claude-sonnet-4-5',
    temperature: 0.6,
    maxHistoryMessages: 30,
    conversationIdleMinutes: 15,
    maxToolRounds: 6,
    userName: 'אבי',
    systemPromptExtra: '',
  },
  limits: { dailyUsd: 2, monthlyUsd: 30, requestsPerMinute: 12 },
  pricing: {
    llm: {
      'claude-sonnet-4-5': { in: 3, out: 15 },
      'claude-haiku-4-5': { in: 1, out: 5 },
      'claude-opus-4-1': { in: 15, out: 75 },
      'gpt-4.1': { in: 2, out: 8 },
      'gpt-4.1-mini': { in: 0.4, out: 1.6 },
      'gpt-4o': { in: 2.5, out: 10 },
      'gpt-4o-mini': { in: 0.15, out: 0.6 },
      default: { in: 5, out: 20 },
    },
    stt: { 'gpt-4o-transcribe': 0.006, 'gpt-4o-mini-transcribe': 0.003, 'whisper-1': 0.006, default: 0.006 },
    tts: { azure: 16, 'gpt-4o-mini-tts': 12, 'tts-1': 15, default: 16 },
  },
  voice: {
    sttModel: 'gpt-4o-transcribe',
    ttsProvider: 'azure',
    azureRegion: 'westeurope',
    azureVoice: 'he-IL-AvriNeural',
    azureRate: '-4%',
    azurePitch: '-10%',
    azureStyle: '',
    openaiVoice: 'onyx',
    openaiTtsModel: 'gpt-4o-mini-tts',
    ackPhrase: 'כן אבי, אני מקשיב.',
    followUpSeconds: 8,
  },
  wake: { enabled: true, threshold: 0.5, patience: 2 },
  station: { slideshowSeconds: 15, idleReturnSeconds: 60, showClock: true, orbQuality: 'auto' },
  google: {
    clientId: '',
    photosFolderId: '',
    photosAlbumUrl: '',
    syncMinutes: 30,
    connectedEmail: '',
    scopes: [
      'openid',
      'email',
      'https://www.googleapis.com/auth/gmail.readonly',
      'https://www.googleapis.com/auth/calendar.events',
      'https://www.googleapis.com/auth/drive.readonly',
    ],
  },
};

export type SettingsKey = keyof AllSettings;

export const SECRET_KEYS = [
  'openai_api_key',
  'anthropic_api_key',
  'azure_speech_key',
  'google_client_secret',
  'google_tokens',
  'ha_token',
  'mqtt_password',
] as const;
export type SecretKey = (typeof SECRET_KEYS)[number];

const cache = new Map<string, unknown>();

export async function getSettings<K extends SettingsKey>(key: K): Promise<AllSettings[K]> {
  if (cache.has(key)) return cache.get(key) as AllSettings[K];
  const row = await q1<{ value: any }>('SELECT value FROM settings WHERE key=$1', [key]);
  const merged = mergeDeep(structuredClone(DEFAULTS[key]), row?.value ?? {}) as AllSettings[K];
  cache.set(key, merged);
  return merged;
}

export async function getAllSettings(): Promise<AllSettings> {
  const out: any = {};
  for (const k of Object.keys(DEFAULTS) as SettingsKey[]) out[k] = await getSettings(k);
  return out;
}

export async function setSettings<K extends SettingsKey>(key: K, patch: Partial<AllSettings[K]>): Promise<AllSettings[K]> {
  const current = await getSettings(key);
  const next = mergeDeep(structuredClone(current), patch) as AllSettings[K];
  await q(
    `INSERT INTO settings(key, value, updated_at) VALUES ($1, $2, now())
     ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value, updated_at=now()`,
    [key, JSON.stringify(next)],
  );
  cache.set(key, next);
  return next;
}

export async function getSecret(key: SecretKey): Promise<string | null> {
  const row = await q1<{ secret_enc: string | null }>('SELECT secret_enc FROM settings WHERE key=$1', [`secret:${key}`]);
  if (!row?.secret_enc) return null;
  try {
    return decrypt(row.secret_enc);
  } catch {
    return null;
  }
}

export async function setSecret(key: SecretKey, value: string | null) {
  if (value === null || value === '') {
    await q('DELETE FROM settings WHERE key=$1', [`secret:${key}`]);
    return;
  }
  await q(
    `INSERT INTO settings(key, secret_enc, updated_at) VALUES ($1, $2, now())
     ON CONFLICT (key) DO UPDATE SET secret_enc=EXCLUDED.secret_enc, updated_at=now()`,
    [`secret:${key}`, encrypt(value)],
  );
}

/** Which secrets are set — never returns values. */
export async function secretStatus(): Promise<Record<SecretKey, boolean>> {
  const rows = await q<{ key: string }>(`SELECT key FROM settings WHERE key LIKE 'secret:%' AND secret_enc IS NOT NULL`);
  const set = new Set(rows.map((r) => r.key.slice(7)));
  return Object.fromEntries(SECRET_KEYS.map((k) => [k, set.has(k)])) as Record<SecretKey, boolean>;
}

export function clearSettingsCache() {
  cache.clear();
}

function mergeDeep(target: any, src: any): any {
  if (typeof src !== 'object' || src === null || Array.isArray(src)) return src ?? target;
  for (const [k, v] of Object.entries(src)) {
    if (v && typeof v === 'object' && !Array.isArray(v) && typeof target[k] === 'object' && target[k] !== null && !Array.isArray(target[k])) {
      target[k] = mergeDeep(target[k], v);
    } else if (v !== undefined) {
      target[k] = v;
    }
  }
  return target;
}
