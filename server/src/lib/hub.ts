import type { WebSocket } from 'ws';

export interface DeviceConn {
  deviceId: string;
  name: string;
  socket: WebSocket;
  connectedAt: number;
  lastPing: number;
  state: string;
  info: Record<string, unknown>;
}

const devices = new Map<string, DeviceConn>();
const admins = new Set<WebSocket>();

export function addDevice(c: DeviceConn) {
  const prev = devices.get(c.deviceId);
  if (prev && prev.socket !== c.socket) {
    try {
      prev.socket.close(4000, 'replaced');
    } catch {}
  }
  devices.set(c.deviceId, c);
  broadcastAdmin({ type: 'device', deviceId: c.deviceId, online: true });
}

export function removeDevice(deviceId: string, socket: WebSocket) {
  const cur = devices.get(deviceId);
  if (cur?.socket === socket) {
    devices.delete(deviceId);
    broadcastAdmin({ type: 'device', deviceId, online: false });
  }
}

export function getDeviceConn(deviceId: string) {
  return devices.get(deviceId);
}

export function onlineDevices() {
  return [...devices.values()].map((d) => ({
    deviceId: d.deviceId,
    name: d.name,
    connectedAt: d.connectedAt,
    lastPing: d.lastPing,
    state: d.state,
    info: d.info,
  }));
}

export function sendToDevice(deviceId: string, msg: unknown): boolean {
  const c = devices.get(deviceId);
  if (!c || c.socket.readyState !== 1) return false;
  c.socket.send(JSON.stringify(msg));
  return true;
}

export function broadcastDevices(msg: unknown) {
  const s = JSON.stringify(msg);
  for (const c of devices.values()) if (c.socket.readyState === 1) c.socket.send(s);
}

export function addAdmin(ws: WebSocket) {
  admins.add(ws);
  ws.on('close', () => admins.delete(ws));
}

export function broadcastAdmin(msg: unknown) {
  const s = JSON.stringify({ ...(msg as object), ts: Date.now() });
  for (const ws of admins) if (ws.readyState === 1) ws.send(s);
}
