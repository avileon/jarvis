import { registerTool } from './registry.js';
import * as google from '../google/google.js';
import { executeAction, findDevice, listDevices } from '../home/home.js';

const googleReady = () => google.isConnected();

function dayRange(dateIso?: string, days = 1) {
  // Start of the given local (Israel) day → ISO with offset.
  const base = dateIso ? new Date(`${dateIso.slice(0, 10)}T00:00:00`) : new Date(new Date().toLocaleString('en-US', { timeZone: 'Asia/Jerusalem' }));
  base.setHours(0, 0, 0, 0);
  const fmt = (d: Date) => {
    const y = d.getFullYear(), m = String(d.getMonth() + 1).padStart(2, '0'), dd = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${dd}`;
  };
  const end = new Date(base);
  end.setDate(end.getDate() + days);
  const offset = israelOffset(base);
  return { timeMin: `${fmt(base)}T00:00:00${offset}`, timeMax: `${fmt(end)}T00:00:00${offset}` };
}

function israelOffset(d: Date) {
  const s = new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Jerusalem', timeZoneName: 'longOffset' }).formatToParts(d).find((p) => p.type === 'timeZoneName')?.value ?? 'GMT+02:00';
  return s.replace('GMT', '') || '+00:00';
}

registerTool({
  name: 'calendar_list_events',
  description: 'רשימת אירועים ביומן Google של המשתמש. ברירת מחדל: היום. date בפורמט YYYY-MM-DD (זמן ישראל).',
  parameters: {
    type: 'object',
    properties: {
      date: { type: 'string', description: 'YYYY-MM-DD. ריק = היום' },
      days: { type: 'integer', minimum: 1, maximum: 14, description: 'מספר ימים (ברירת מחדל 1)' },
      query: { type: 'string', description: 'חיפוש טקסט חופשי (אופציונלי)' },
    },
  },
  untrustedOutput: true,
  available: googleReady,
  run: async (a) => {
    const r = dayRange(a.date as string | undefined, Number(a.days ?? 1));
    return google.listEvents({ ...r, query: a.query as string | undefined });
  },
});

registerTool({
  name: 'calendar_create_event',
  description: 'יצירת אירוע ביומן. דורש אישור מפורש של המשתמש (המערכת מטפלת בזה). זמנים בפורמט ISO מקומי YYYY-MM-DDTHH:MM:SS.',
  parameters: {
    type: 'object',
    required: ['title', 'start', 'end'],
    properties: {
      title: { type: 'string' },
      start: { type: 'string' },
      end: { type: 'string' },
      location: { type: 'string' },
      description: { type: 'string' },
    },
  },
  sensitive: true,
  action: true,
  available: googleReady,
  describe: (a) => `ליצור ביומן "${a.title}" ב-${String(a.start).replace('T', ' ').slice(0, 16)}`,
  run: async (a) => {
    const r = await google.createEvent(a as any);
    return { ...r, say: `האירוע "${a.title}" נוסף ליומן.` };
  },
});

registerTool({
  name: 'gmail_search',
  description: 'חיפוש מיילים ב-Gmail. מקבל שאילתת Gmail (למשל "is:unread newer_than:1d" או "from:dana"). מחזיר מטא-דאטה ותקציר.',
  parameters: {
    type: 'object',
    properties: { query: { type: 'string' }, max: { type: 'integer', minimum: 1, maximum: 15 } },
  },
  untrustedOutput: true,
  available: googleReady,
  run: async (a) => google.searchMail(String(a.query ?? 'in:inbox newer_than:2d'), Number(a.max ?? 8)),
});

registerTool({
  name: 'gmail_read',
  description: 'קריאת תוכן מלא של מייל לפי id (מתוך gmail_search).',
  parameters: { type: 'object', required: ['id'], properties: { id: { type: 'string' } } },
  untrustedOutput: true,
  available: googleReady,
  run: async (a) => google.readMail(String(a.id)),
});

registerTool({
  name: 'home_list_devices',
  description: 'רשימת מכשירי הבית החכם המוגדרים והפעולות האפשריות לכל אחד (כולל תחנות רדיו שמתנגנות בטאבלט).',
  parameters: { type: 'object', properties: {} },
  run: async () =>
    (await listDevices(false)).map((d) => ({ name: d.name, aliases: d.aliases, room: d.room, actions: d.actions.map((x) => ({ id: x.id, label: x.label })) })),
});

registerTool({
  name: 'home_control',
  description:
    'הפעלת מכשיר בבית החכם או ניגון רדיו/מדיה בטאבלט. device = שם המכשיר כפי שהמשתמש אמר; action = מזהה פעולה (on/off/play/stop/temperature וכו\' — ראה home_list_devices); value לפעולות עם ערך.',
  parameters: {
    type: 'object',
    required: ['device', 'action'],
    properties: { device: { type: 'string' }, action: { type: 'string' }, value: { type: ['string', 'number'] } },
  },
  action: true,
  needsConfirmation: async (a) => !!(await findDevice(String(a.device)))?.sensitive,
  describe: async (a) => {
    const d = await findDevice(String(a.device));
    const act = d?.actions.find((x) => x.id === a.action);
    return `${act?.label ?? a.action} — ${d?.name ?? a.device}${a.value !== undefined ? ` (${a.value})` : ''}`;
  },
  run: async (a, ctx) => {
    const d = await findDevice(String(a.device));
    if (!d) {
      const names = (await listDevices(false)).map((x) => x.name);
      throw new Error(`לא נמצא מכשיר בשם "${a.device}". מכשירים מוגדרים: ${names.join(', ') || 'אין עדיין'}`);
    }
    await executeAction(d, String(a.action), a.value, ctx.deviceId);
    return { ok: true, device: d.name, action: a.action };
  },
});
