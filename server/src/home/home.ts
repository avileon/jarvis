import mqtt, { type MqttClient } from 'mqtt';
import { z } from 'zod';
import { decrypt, encrypt } from '../lib/crypto.js';
import { q, q1 } from '../lib/db.js';
import { getSecret, getSettings } from '../lib/settings.js';
import { sendToDevice, broadcastDevices } from '../lib/hub.js';

export const ADAPTERS = ['homeassistant', 'rest', 'webhook', 'mqtt', 'tablet_media'] as const;
export type Adapter = (typeof ADAPTERS)[number];

export const actionSchema = z.object({
  id: z.string().min(1).max(40),
  label: z.string().min(1).max(60),
  // homeassistant
  service: z.string().optional(),
  data: z.record(z.any()).optional(),
  // rest / webhook
  method: z.enum(['GET', 'POST', 'PUT', 'PATCH', 'DELETE']).optional(),
  url: z.string().optional(),
  headers: z.record(z.string()).optional(),
  body: z.string().optional(),
  // mqtt
  topic: z.string().optional(),
  payload: z.string().optional(),
  retain: z.boolean().optional(),
  // tablet_media
  mediaUrl: z.string().optional(),
});
export type DeviceAction = z.infer<typeof actionSchema>;

export const deviceSchema = z.object({
  name: z.string().min(1).max(80),
  aliases: z.array(z.string().max(80)).default([]),
  room: z.string().max(60).optional().nullable(),
  adapter: z.enum(ADAPTERS),
  config: z.object({ entityId: z.string().optional() }).passthrough().default({}),
  actions: z.array(actionSchema).min(1),
  sensitive: z.boolean().default(false),
  enabled: z.boolean().default(true),
});
export type DeviceInput = z.infer<typeof deviceSchema>;

export interface SmartDevice extends DeviceInput {
  id: number;
}

export async function listDevices(includeDisabled = true): Promise<SmartDevice[]> {
  const rows = await q<any>(`SELECT * FROM smart_devices ${includeDisabled ? '' : 'WHERE enabled'} ORDER BY room NULLS LAST, name`);
  return rows.map(rowToDevice);
}

function rowToDevice(r: any): SmartDevice {
  const secret = JSON.parse(decrypt(r.config_enc));
  return { id: r.id, name: r.name, aliases: r.aliases, room: r.room, adapter: r.adapter, sensitive: r.sensitive, enabled: r.enabled, config: secret.config, actions: secret.actions };
}

export async function saveDevice(input: DeviceInput, id?: number): Promise<number> {
  const d = deviceSchema.parse(input);
  const enc = encrypt(JSON.stringify({ config: d.config, actions: d.actions }));
  const publicActions = JSON.stringify(d.actions.map((a) => ({ id: a.id, label: a.label })));
  if (id) {
    await q(`UPDATE smart_devices SET name=$1, aliases=$2, room=$3, adapter=$4, config_enc=$5, actions=$6, sensitive=$7, enabled=$8 WHERE id=$9`, [
      d.name, d.aliases, d.room ?? null, d.adapter, enc, publicActions, d.sensitive, d.enabled, id,
    ]);
    return id;
  }
  const row = await q1<{ id: number }>(
    `INSERT INTO smart_devices(name, aliases, room, adapter, config_enc, actions, sensitive, enabled) VALUES ($1,$2,$3,$4,$5,$6,$7,$8) RETURNING id`,
    [d.name, d.aliases, d.room ?? null, d.adapter, enc, publicActions, d.sensitive, d.enabled],
  );
  return row!.id;
}

export async function deleteDevice(id: number) {
  await q('DELETE FROM smart_devices WHERE id=$1', [id]);
}

const norm = (s: string) => s.trim().toLowerCase().replace(/["'׳״`]/g, '').replace(/^ה(?=\S{3,})/, '').replace(/\s+/g, ' ');

/** Resolve a spoken device name ("האור בסלון", "גלגלצ") to a configured device. */
export async function findDevice(name: string): Promise<SmartDevice | null> {
  const devices = (await listDevices(false));
  const n = norm(name);
  const candidates = devices.map((d) => ({ d, names: [d.name, ...d.aliases, d.room ? `${d.name} ב${d.room}` : ''].filter(Boolean).map(norm) }));
  return (
    candidates.find((c) => c.names.includes(n))?.d ??
    candidates.find((c) => c.names.some((x) => x.includes(n) || n.includes(x)))?.d ??
    null
  );
}

function fill(s: string | undefined, value: unknown) {
  return (s ?? '').replace(/\{\{\s*value\s*\}\}/g, value === undefined ? '' : String(value));
}
function fillDeep(o: unknown, value: unknown): unknown {
  if (typeof o === 'string') {
    if (/^\{\{\s*value\s*\}\}$/.test(o) && value !== undefined) return isNaN(Number(value)) ? value : Number(value);
    return fill(o, value);
  }
  if (Array.isArray(o)) return o.map((x) => fillDeep(x, value));
  if (o && typeof o === 'object') return Object.fromEntries(Object.entries(o).map(([k, v]) => [k, fillDeep(v, value)]));
  return o;
}

let mqttClient: MqttClient | null = null;
async function getMqtt(): Promise<MqttClient> {
  if (mqttClient?.connected) return mqttClient;
  const h = await getSettings('home');
  if (!h.mqttUrl) throw new Error('MQTT לא הוגדר');
  const password = (await getSecret('mqtt_password')) ?? undefined;
  mqttClient?.end(true);
  mqttClient = mqtt.connect(h.mqttUrl, { username: h.mqttUsername || undefined, password, connectTimeout: 8000, reconnectPeriod: 5000 });
  await new Promise<void>((resolve, reject) => {
    mqttClient!.once('connect', () => resolve());
    mqttClient!.once('error', reject);
    setTimeout(() => reject(new Error('MQTT timeout')), 9000);
  });
  return mqttClient;
}

async function haCall(service: string, data: Record<string, unknown>) {
  const h = await getSettings('home');
  const token = await getSecret('ha_token');
  if (!h.haUrl || !token) throw new Error('Home Assistant לא הוגדר');
  const [domain, svc] = service.split('.');
  const res = await fetch(`${h.haUrl.replace(/\/$/, '')}/api/services/${domain}/${svc}`, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify(data),
    signal: AbortSignal.timeout(15_000),
  });
  if (!res.ok) throw new Error(`Home Assistant ${res.status}: ${(await res.text()).slice(0, 200)}`);
  return { ok: true };
}

export async function haStates() {
  const h = await getSettings('home');
  const token = await getSecret('ha_token');
  if (!h.haUrl || !token) throw new Error('Home Assistant לא הוגדר');
  const res = await fetch(`${h.haUrl.replace(/\/$/, '')}/api/states`, { headers: { authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(15_000) });
  if (!res.ok) throw new Error(`Home Assistant ${res.status}`);
  const data: any[] = await res.json();
  return data.map((s) => ({ entityId: s.entity_id, name: s.attributes?.friendly_name ?? s.entity_id, state: s.state }));
}

/** Default actions for a Home Assistant entity by its domain. */
export function haDefaultActions(entityId: string): DeviceAction[] {
  const domain = entityId.split('.')[0];
  const e = { entity_id: entityId };
  switch (domain) {
    case 'light':
      return [
        { id: 'on', label: 'הדלק', service: 'light.turn_on', data: e },
        { id: 'off', label: 'כבה', service: 'light.turn_off', data: e },
        { id: 'brightness', label: 'עוצמה (%)', service: 'light.turn_on', data: { ...e, brightness_pct: '{{value}}' } },
      ];
    case 'climate':
      return [
        { id: 'on', label: 'הפעל', service: 'climate.turn_on', data: e },
        { id: 'off', label: 'כבה', service: 'climate.turn_off', data: e },
        { id: 'temperature', label: 'קבע טמפרטורה', service: 'climate.set_temperature', data: { ...e, temperature: '{{value}}' } },
      ];
    case 'cover':
      return [
        { id: 'open', label: 'פתח', service: 'cover.open_cover', data: e },
        { id: 'close', label: 'סגור', service: 'cover.close_cover', data: e },
      ];
    case 'media_player':
      return [
        { id: 'play', label: 'נגן', service: 'media_player.media_play', data: e },
        { id: 'pause', label: 'השהה', service: 'media_player.media_pause', data: e },
        { id: 'volume', label: 'ווליום (0-1)', service: 'media_player.volume_set', data: { ...e, volume_level: '{{value}}' } },
      ];
    case 'scene':
      return [{ id: 'activate', label: 'הפעל', service: 'scene.turn_on', data: e }];
    case 'script':
      return [{ id: 'run', label: 'הרץ', service: 'script.turn_on', data: e }];
    case 'lock':
      return [
        { id: 'lock', label: 'נעל', service: 'lock.lock', data: e },
        { id: 'unlock', label: 'פתח נעילה', service: 'lock.unlock', data: e },
      ];
    default:
      return [
        { id: 'on', label: 'הפעל', service: 'homeassistant.turn_on', data: e },
        { id: 'off', label: 'כבה', service: 'homeassistant.turn_off', data: e },
      ];
  }
}

export async function executeAction(device: SmartDevice, actionId: string, value: unknown, deviceId?: string) {
  const action = device.actions.find((a) => a.id === actionId) ?? device.actions.find((a) => a.label === actionId);
  if (!action) throw new Error(`לפעולה "${actionId}" אין הגדרה במכשיר ${device.name}. פעולות זמינות: ${device.actions.map((a) => a.id).join(', ')}`);

  switch (device.adapter) {
    case 'homeassistant':
      if (!action.service) throw new Error('חסר service');
      return haCall(action.service, fillDeep(action.data ?? { entity_id: device.config.entityId }, value) as Record<string, unknown>);
    case 'rest':
    case 'webhook': {
      if (!action.url) throw new Error('חסר URL');
      const method = action.method ?? 'POST';
      const res = await fetch(fill(action.url, value), {
        method,
        headers: { 'content-type': 'application/json', ...(action.headers ?? {}) },
        body: method === 'GET' ? undefined : fill(action.body ?? action.payload ?? '{}', value),
        signal: AbortSignal.timeout(15_000),
      });
      if (!res.ok) throw new Error(`${device.adapter} ${res.status}`);
      return { ok: true, status: res.status };
    }
    case 'mqtt': {
      if (!action.topic) throw new Error('חסר topic');
      const client = await getMqtt();
      await new Promise<void>((resolve, reject) =>
        client.publish(fill(action.topic, value), fill(action.payload, value), { qos: 1, retain: !!action.retain }, (err) => (err ? reject(err) : resolve())),
      );
      return { ok: true };
    }
    case 'tablet_media': {
      const msg =
        action.id === 'stop' || action.id === 'off'
          ? { type: 'media', action: 'stop' }
          : { type: 'media', action: 'play', url: fill(action.mediaUrl ?? action.url, value), name: device.name };
      const sent = deviceId ? sendToDevice(deviceId, msg) : (broadcastDevices(msg), true);
      if (!sent) throw new Error('הטאבלט לא מחובר');
      return { ok: true };
    }
  }
}
