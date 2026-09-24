import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { adm, del, fmtDate, post, put, usd } from './api';

type PageProps = { events: any[] };

// ---------- shared ----------
function useLoad<T>(path: string, deps: unknown[] = []): [T | null, () => void, string] {
  const [data, setData] = useState<T | null>(null);
  const [err, setErr] = useState('');
  const load = useCallback(() => {
    adm<T>(path).then((d) => { setData(d); setErr(''); }).catch((e) => setErr(e.message));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, ...deps]);
  useEffect(load, [load]);
  return [data, load, err];
}

function useSettings<T = any>(key: string) {
  const [all, reload] = useLoad<any>('/api/admin/settings');
  const [draft, setDraft] = useState<T | null>(null);
  const [msg, setMsg] = useState('');
  useEffect(() => {
    if (all) setDraft(all.settings[key]);
  }, [all, key]);
  const save = async (patch?: Partial<T>) => {
    try {
      const next = await put(`/api/admin/settings/${key}`, patch ?? draft);
      setDraft(next);
      setMsg('נשמר ✓');
      setTimeout(() => setMsg(''), 2500);
      reload();
    } catch (e: any) {
      setMsg(`שגיאה: ${e.message}`);
    }
  };
  const set = (k: keyof T, v: any) => setDraft((d) => ({ ...(d as any), [k]: v }));
  return { draft, set, save, msg, secrets: all?.secrets as Record<string, boolean> | undefined, reload };
}

function Card({ title, children, actions }: { title: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <div className="card">
      <div className="card-h">
        <h3>{title}</h3>
        <div>{actions}</div>
      </div>
      {children}
    </div>
  );
}

function Field({ label, hint, children }: { label: string; hint?: ReactNode; children: ReactNode }) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
      {hint && <small>{hint}</small>}
    </label>
  );
}

function Num({ value, onChange, step = 1, min, max }: { value: number; onChange: (n: number) => void; step?: number; min?: number; max?: number }) {
  return <input type="number" value={value} step={step} min={min} max={max} onChange={(e) => onChange(Number(e.target.value))} />;
}

function SecretInput({ name, label, isSet, onSaved, hint }: { name: string; label: string; isSet?: boolean; onSaved?: () => void; hint?: ReactNode }) {
  const [v, setV] = useState('');
  const [msg, setMsg] = useState('');
  return (
    <Field label={label} hint={hint}>
      <div className="row">
        <input type="password" autoComplete="off" placeholder={isSet ? '•••••••• (מוגדר)' : 'לא מוגדר'} value={v} onChange={(e) => setV(e.target.value)} />
        <button
          disabled={!v}
          onClick={async () => {
            await put(`/api/admin/secrets/${name}`, { value: v });
            setV('');
            setMsg('נשמר ✓');
            onSaved?.();
          }}
        >
          שמור
        </button>
        {isSet && (
          <button className="ghost" onClick={async () => { if (confirm('למחוק את המפתח?')) { await put(`/api/admin/secrets/${name}`, { value: null }); onSaved?.(); } }}>
            מחק
          </button>
        )}
        <span className="ok">{msg}</span>
      </div>
    </Field>
  );
}

function Badge({ ok, children }: { ok: boolean; children: ReactNode }) {
  return <span className={`badge ${ok ? 'good' : 'bad'}`}>{children}</span>;
}

// ---------- dashboard ----------
export function Dashboard({ events }: PageProps) {
  const [s, reload] = useLoad<any>('/api/admin/status');
  useEffect(() => {
    const t = setInterval(reload, 10000);
    return () => clearInterval(t);
  }, [reload]);
  useEffect(() => {
    if (events[0]?.type === 'device') reload();
  }, [events, reload]);
  if (!s) return <div>טוען…</div>;
  return (
    <>
      <h2>לוח בקרה</h2>
      <div className="grid4">
        <div className="stat"><b>{s.online.length}</b><span>טאבלטים מחוברים</span></div>
        <div className="stat"><b>{usd(s.spend.day)}</b><span>עלות היום · {usd(s.spend.month)} החודש</span></div>
        <div className="stat"><b className={s.errors24h ? 'warn' : ''}>{s.errors24h}</b><span>שגיאות ב-24 שעות</span></div>
        <div className="stat"><b>{s.photos}</b><span>תמונות במצגת</span></div>
      </div>
      <Card title="מצב חיבורים">
        <div className="chips">
          <Badge ok={s.secrets.anthropic_api_key}>Anthropic</Badge>
          <Badge ok={s.secrets.openai_api_key}>OpenAI (AI + זיהוי דיבור)</Badge>
          <Badge ok={s.secrets.azure_speech_key}>Azure Speech (קול)</Badge>
          <Badge ok={s.google.connected}>Google {s.google.email && `· ${s.google.email}`}</Badge>
          <Badge ok={!!s.google.folder}>תיקיית תמונות</Badge>
        </div>
      </Card>
      <Card title="טאבלטים">
        {s.online.length === 0 && <p className="muted">אין טאבלט מחובר כרגע.</p>}
        <table>
          <tbody>
            {s.online.map((d: any) => (
              <tr key={d.deviceId}>
                <td><span className="dot on" /> {d.name}</td>
                <td>מצב: {d.state}</td>
                <td>מחובר מאז {fmtDate(d.connectedAt)}</td>
                <td>פינג אחרון {fmtDate(d.lastPing)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
      <Card title="אירועים חיים">
        {events.length === 0 && <p className="muted">ממתין לאירועים…</p>}
        <ul className="events">
          {events.map((e, i) => (
            <li key={i} className={e.type}>
              <time>{new Date(e.ts).toLocaleTimeString('he-IL')}</time> {e.type === 'error' ? `⚠ ${e.source}: ${e.message}` : e.type === 'action' ? `⚙ ${e.summary} (${e.status})` : `📟 טאבלט ${e.online ? 'התחבר' : 'התנתק'}`}
            </li>
          ))}
        </ul>
      </Card>
    </>
  );
}

// ---------- AI ----------
export function AiPage() {
  const ai = useSettings<any>('ai');
  const limits = useSettings<any>('limits');
  const pricing = useSettings<any>('pricing');
  const [models, setModels] = useState<string[]>([]);
  const [modelErr, setModelErr] = useState('');
  const [test, setTest] = useState('מה השעה ומה התאריך היום?');
  const [testOut, setTestOut] = useState('');

  useEffect(() => {
    if (!ai.draft?.provider) return;
    setModels([]);
    setModelErr('');
    adm(`/api/admin/models/${ai.draft.provider}`).then((r) => setModels(r.models)).catch((e) => setModelErr(e.message));
  }, [ai.draft?.provider, ai.secrets?.anthropic_api_key, ai.secrets?.openai_api_key]);

  if (!ai.draft || !limits.draft || !pricing.draft) return <div>טוען…</div>;
  return (
    <>
      <h2>בינה מלאכותית</h2>
      <Card title="מפתחות API" >
        <p className="muted">המפתחות נשמרים מוצפנים (AES-256-GCM) בשרת בלבד, ואינם נשלחים לטאבלט.</p>
        <SecretInput name="anthropic_api_key" label="Anthropic API key" isSet={ai.secrets?.anthropic_api_key} onSaved={ai.reload} />
        <SecretInput name="openai_api_key" label="OpenAI API key" isSet={ai.secrets?.openai_api_key} onSaved={ai.reload} hint="משמש גם לזיהוי דיבור בעברית (gpt-4o-transcribe)" />
      </Card>
      <Card title="מודל ושיחה" actions={<><span className="ok">{ai.msg}</span><button onClick={() => ai.save()}>שמור</button></>}>
        <div className="grid2">
          <Field label="ספק">
            <select value={ai.draft.provider} onChange={(e) => ai.set('provider', e.target.value)}>
              <option value="anthropic">Anthropic (Claude)</option>
              <option value="openai">OpenAI</option>
            </select>
          </Field>
          <Field label="מודל" hint={modelErr ? `לא ניתן לטעון רשימה: ${modelErr}` : 'הרשימה נטענת מה-API של הספק'}>
            <input list="models" value={ai.draft.model} onChange={(e) => ai.set('model', e.target.value)} />
            <datalist id="models">{models.map((m) => <option key={m} value={m} />)}</datalist>
          </Field>
          <Field label="טמפרטורה"><Num value={ai.draft.temperature} step={0.1} min={0} max={1} onChange={(v) => ai.set('temperature', v)} /></Field>
          <Field label="הודעות היסטוריה בהקשר"><Num value={ai.draft.maxHistoryMessages} min={4} max={200} onChange={(v) => ai.set('maxHistoryMessages', v)} /></Field>
          <Field label="שיחה חדשה אחרי (דקות שקט)"><Num value={ai.draft.conversationIdleMinutes} min={1} max={1440} onChange={(v) => ai.set('conversationIdleMinutes', v)} /></Field>
          <Field label="איך לפנות אליך"><input value={ai.draft.userName} onChange={(e) => ai.set('userName', e.target.value)} /></Field>
        </div>
        <Field label="הנחיות נוספות לג׳ארביס" hint="למשל: סגנון, מידע קבוע עליך, העדפות.">
          <textarea rows={4} value={ai.draft.systemPromptExtra} onChange={(e) => ai.set('systemPromptExtra', e.target.value)} />
        </Field>
      </Card>
      <Card title="בדיקה">
        <div className="row">
          <input value={test} onChange={(e) => setTest(e.target.value)} />
          <button onClick={async () => { setTestOut('…'); const r = await post('/api/admin/test/chat', { text: test }); setTestOut(r.error ? `שגיאה: ${r.error}` : r.reply); }}>שלח</button>
        </div>
        {testOut && <div className="reply">{testOut}</div>}
      </Card>
      <Card title="הגבלות שימוש" actions={<><span className="ok">{limits.msg}</span><button onClick={() => limits.save()}>שמור</button></>}>
        <div className="grid3">
          <Field label="תקרה יומית ($)"><Num value={limits.draft.dailyUsd} step={0.5} min={0} onChange={(v) => limits.set('dailyUsd', v)} /></Field>
          <Field label="תקרה חודשית ($)"><Num value={limits.draft.monthlyUsd} step={1} min={0} onChange={(v) => limits.set('monthlyUsd', v)} /></Field>
          <Field label="בקשות לדקה"><Num value={limits.draft.requestsPerMinute} min={0} onChange={(v) => limits.set('requestsPerMinute', v)} /></Field>
        </div>
        <small className="muted">0 = ללא הגבלה.</small>
      </Card>
      <Card title="מחירון לחישוב עלויות (USD)" actions={<><span className="ok">{pricing.msg}</span><button onClick={() => pricing.save()}>שמור</button></>}>
        <p className="muted">LLM: למיליון טוקנים (קלט/פלט). STT: לדקה. TTS: למיליון תווים. יש לוודא מול דפי המחירים של הספקים.</p>
        <textarea className="mono" rows={14} defaultValue={JSON.stringify(pricing.draft, null, 2)} onBlur={(e) => { try { pricing.set('llm', JSON.parse(e.target.value).llm); pricing.set('stt', JSON.parse(e.target.value).stt); pricing.set('tts', JSON.parse(e.target.value).tts); } catch { alert('JSON לא תקין'); } }} />
      </Card>
    </>
  );
}

// ---------- voice ----------
export function VoicePage() {
  const v = useSettings<any>('voice');
  const w = useSettings<any>('wake');
  const st = useSettings<any>('station');
  const [sample, setSample] = useState("שלום אבי. כל המערכות פועלות כשורה. במה אוכל לעזור?");
  const [err, setErr] = useState('');
  if (!v.draft || !w.draft || !st.draft) return <div>טוען…</div>;
  return (
    <>
      <h2>קול והאזנה</h2>
      <Card title="הקראה (TTS)" actions={<><span className="ok">{v.msg}</span><button onClick={() => v.save()}>שמור</button></>}>
        <SecretInput name="azure_speech_key" label="Azure Speech key" isSet={v.secrets?.azure_speech_key} onSaved={v.reload} hint="Azure Portal → Speech service → Keys and Endpoint" />
        <div className="grid3">
          <Field label="מנוע">
            <select value={v.draft.ttsProvider} onChange={(e) => v.set('ttsProvider', e.target.value)}>
              <option value="azure">Azure Neural (עברית טבעית)</option>
              <option value="openai">OpenAI TTS</option>
            </select>
          </Field>
          <Field label="Azure region"><input value={v.draft.azureRegion} onChange={(e) => v.set('azureRegion', e.target.value)} /></Field>
          <Field label="קול Azure" hint="he-IL-AvriNeural = גברי"><input value={v.draft.azureVoice} onChange={(e) => v.set('azureVoice', e.target.value)} /></Field>
          <Field label="קצב" hint="למשל -4%"><input value={v.draft.azureRate} onChange={(e) => v.set('azureRate', e.target.value)} /></Field>
          <Field label="גובה (pitch)" hint="שלילי = עמוק יותר"><input value={v.draft.azurePitch} onChange={(e) => v.set('azurePitch', e.target.value)} /></Field>
          <Field label="קול OpenAI"><input value={v.draft.openaiVoice} onChange={(e) => v.set('openaiVoice', e.target.value)} /></Field>
        </div>
        <div className="row">
          <input value={sample} onChange={(e) => setSample(e.target.value)} />
          <button
            onClick={async () => {
              setErr('');
              try {
                await v.save();
                const blob = await adm<Blob>('/api/admin/test/tts', { method: 'POST', json: { text: sample } });
                new Audio(URL.createObjectURL(blob)).play();
              } catch (e: any) {
                setErr(e.message);
              }
            }}
          >
            ▶ השמע
          </button>
        </div>
        {err && <div className="err">{err}</div>}
      </Card>
      <Card title="שיחה" actions={<button onClick={() => v.save()}>שמור</button>}>
        <div className="grid3">
          <Field label="תגובה למילת ההפעלה"><input value={v.draft.ackPhrase} onChange={(e) => v.set('ackPhrase', e.target.value)} /></Field>
          <Field label="המשך האזנה אחרי תשובה (שניות)" hint="שאלות המשך בלי 'היי ג׳ארביס'. 0 = כבוי"><Num value={v.draft.followUpSeconds} min={0} max={30} onChange={(n) => v.set('followUpSeconds', n)} /></Field>
          <Field label="מודל זיהוי דיבור"><input value={v.draft.sttModel} onChange={(e) => v.set('sttModel', e.target.value)} /></Field>
        </div>
      </Card>
      <Card title='מילת הפעלה "היי ג׳ארביס"' actions={<><span className="ok">{w.msg}</span><button onClick={() => w.save()}>שמור</button></>}>
        <p className="muted">openWakeWord רץ בטאבלט עצמו. אין שליחת אודיו לשרת לפני זיהוי מילת ההפעלה.</p>
        <div className="grid3">
          <Field label="פעיל"><select value={String(w.draft.enabled)} onChange={(e) => w.set('enabled', e.target.value === 'true')}><option value="true">כן</option><option value="false">לא</option></select></Field>
          <Field label="סף זיהוי (0-1)" hint="נמוך = רגיש יותר, יותר הפעלות שווא"><Num value={w.draft.threshold} step={0.05} min={0.1} max={0.95} onChange={(n) => w.set('threshold', n)} /></Field>
          <Field label="רצף פריימים נדרש"><Num value={w.draft.patience} min={1} max={6} onChange={(n) => w.set('patience', n)} /></Field>
        </div>
      </Card>
      <Card title="תחנה ומצגת" actions={<><span className="ok">{st.msg}</span><button onClick={() => st.save()}>שמור</button></>}>
        <div className="grid4">
          <Field label="החלפת תמונה (שניות)"><Num value={st.draft.slideshowSeconds} min={3} onChange={(n) => st.set('slideshowSeconds', n)} /></Field>
          <Field label="חזרה למצגת אחרי (שניות)"><Num value={st.draft.idleReturnSeconds} min={10} onChange={(n) => st.set('idleReturnSeconds', n)} /></Field>
          <Field label="שעון במצגת"><select value={String(st.draft.showClock)} onChange={(e) => st.set('showClock', e.target.value === 'true')}><option value="true">כן</option><option value="false">לא</option></select></Field>
          <Field label="איכות גרפיקה"><select value={st.draft.orbQuality} onChange={(e) => st.set('orbQuality', e.target.value)}><option value="auto">אוטומטי</option><option value="low">חסכוני (טאבלט ישן)</option><option value="high">גבוהה</option></select></Field>
        </div>
      </Card>
    </>
  );
}

// ---------- Google ----------
export function GooglePage() {
  const g = useSettings<any>('google');
  const [status] = useLoad<any>('/api/admin/status');
  const [photos, reloadPhotos] = useLoad<any[]>('/api/admin/photos');
  const [syncMsg, setSyncMsg] = useState('');
  const params = new URLSearchParams(location.search);
  if (!g.draft || !status) return <div>טוען…</div>;
  const redirect = `${status.publicUrl.replace(/\/$/, '')}/api/google/callback`;
  return (
    <>
      <h2>Google ותמונות</h2>
      {params.get('connected') && <div className="notice good">החיבור ל-Google הושלם ✓</div>}
      {params.get('error') && <div className="notice bad">שגיאה: {params.get('error')}</div>}
      <Card title="1. פרטי OAuth" actions={<><span className="ok">{g.msg}</span><button onClick={() => g.save()}>שמור</button></>}>
        <ol className="muted small">
          <li>Google Cloud Console (בחשבון avi@vibit.co.il) → APIs & Services → הפעל Gmail API, Google Calendar API, Google Drive API.</li>
          <li>OAuth consent screen → External, הוסף את עצמך כ-Test user.</li>
          <li>Credentials → Create OAuth client ID → Web application.</li>
          <li>Authorized redirect URI: <code>{redirect}</code></li>
        </ol>
        <Field label="Client ID"><input value={g.draft.clientId} onChange={(e) => g.set('clientId', e.target.value)} /></Field>
        <SecretInput name="google_client_secret" label="Client secret" isSet={g.secrets?.google_client_secret} onSaved={g.reload} />
      </Card>
      <Card title="2. חיבור חשבון">
        {status.google.connected ? (
          <div className="row">
            <Badge ok>מחובר {status.google.email}</Badge>
            <button className="ghost" onClick={async () => { if (confirm('לנתק את Google?')) { await post('/api/admin/google/disconnect'); location.reload(); } }}>נתק</button>
          </div>
        ) : (
          <button onClick={async () => { const r = await adm('/api/admin/google/auth'); location.href = r.url; }}>התחבר עם Google</button>
        )}
        <p className="muted small">הרשאות: קריאת מייל (ללא שליחה), יומן (קריאה + יצירת אירועים באישור), Drive לקריאה בלבד. הטוקנים נשמרים מוצפנים.</p>
      </Card>
      <Card title="תמונות למצגת" actions={<button onClick={() => g.save()}>שמור</button>}>
        <Field label="קישור לאלבום משותף ב-Google Photos (מומלץ — לא דורש חיבור Google)" hint="באפליקציית Google Photos: אלבום ← שיתוף ← יצירת קישור. תמונות חדשות באלבום יסונכרנו אוטומטית.">
          <input value={g.draft.photosAlbumUrl ?? ''} onChange={(e) => g.set('photosAlbumUrl', e.target.value)} placeholder="https://photos.app.goo.gl/..." />
        </Field>
        <Field label="או: קישור/מזהה תיקייה ב-Drive (דורש חיבור Google)"><input value={g.draft.photosFolderId} onChange={(e) => g.set('photosFolderId', e.target.value)} placeholder="https://drive.google.com/drive/folders/..." /></Field>
        <Field label="סנכרון אוטומטי כל (דקות)"><Num value={g.draft.syncMinutes} min={5} onChange={(n) => g.set('syncMinutes', n)} /></Field>
        <div className="row">
          <button
            onClick={async () => {
              setSyncMsg('מסנכרן…');
              try {
                const r = await post('/api/admin/photos/sync');
                setSyncMsg(`נוספו ${r.added}, הוסרו ${r.removed}, סה"כ ${r.total}`);
                reloadPhotos();
              } catch (e: any) {
                setSyncMsg(`שגיאה: ${e.message}`);
              }
            }}
          >
            סנכרן עכשיו
          </button>
          <span className="ok">{syncMsg}</span>
        </div>
        <p className="muted">{photos?.length ?? 0} תמונות מסונכרנות. התמונות מוקטנות ל-1920px בשרת ונשמרות מקומית בטאבלט.</p>
      </Card>
    </>
  );
}

// ---------- smart home ----------
const EMPTY_DEVICE = { name: '', aliases: [] as string[], room: '', adapter: 'webhook', config: {}, actions: [{ id: 'on', label: 'הדלק', url: '', method: 'POST', body: '' }], sensitive: false, enabled: true };

export function HomePage() {
  const h = useSettings<any>('home');
  const [devices, reload] = useLoad<any[]>('/api/admin/home/devices');
  const [edit, setEdit] = useState<any | null>(null);
  const [json, setJson] = useState('');
  const [ha, setHa] = useState<any[] | null>(null);
  const [msg, setMsg] = useState('');
  if (!h.draft) return <div>טוען…</div>;

  const open = (d: any) => {
    setEdit(d);
    const { id, ...rest } = d;
    setJson(JSON.stringify(rest, null, 2));
  };

  return (
    <>
      <h2>בית חכם</h2>
      <Card title="Home Assistant / MQTT" actions={<><span className="ok">{h.msg}</span><button onClick={() => h.save()}>שמור</button></>}>
        <div className="grid3">
          <Field label="Home Assistant URL" hint="למשל http://homeassistant.local:8123 (חייב להיות נגיש מהשרת)"><input value={h.draft.haUrl} onChange={(e) => h.set('haUrl', e.target.value)} /></Field>
          <Field label="MQTT broker" hint="mqtt://host:1883 או mqtts://"><input value={h.draft.mqttUrl} onChange={(e) => h.set('mqttUrl', e.target.value)} /></Field>
          <Field label="MQTT user"><input value={h.draft.mqttUsername} onChange={(e) => h.set('mqttUsername', e.target.value)} /></Field>
        </div>
        <SecretInput name="ha_token" label="Home Assistant long-lived token" isSet={h.secrets?.ha_token} onSaved={h.reload} />
        <SecretInput name="mqtt_password" label="MQTT password" isSet={h.secrets?.mqtt_password} onSaved={h.reload} />
        <button className="ghost" onClick={async () => { try { setHa(await adm('/api/admin/home/ha-states')); } catch (e: any) { setMsg(e.message); } }}>ייבא ישויות מ-Home Assistant</button>
        <span className="err"> {msg}</span>
        {ha && (
          <table className="compact">
            <tbody>
              {ha.filter((x) => /^(light|switch|climate|cover|media_player|fan|scene|script|lock)\./.test(x.entityId)).map((x) => (
                <tr key={x.entityId}>
                  <td>{x.name}</td>
                  <td className="mono">{x.entityId}</td>
                  <td>{x.state}</td>
                  <td>
                    <button className="small" onClick={async () => { const name = prompt('שם בעברית (כפי שתגיד לג׳ארביס):', x.name); if (!name) return; const room = prompt('חדר (אופציונלי):') ?? ''; await post('/api/admin/home/ha-import', { entityId: x.entityId, name, room }); reload(); }}>הוסף</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
      <Card title="מכשירים" actions={<button onClick={() => open(EMPTY_DEVICE)}>+ מכשיר חדש</button>}>
        {devices?.length === 0 && <p className="muted">עדיין לא הוגדרו מכשירים. אחרי שתמסור את פרטי המכשירים נגדיר אותם כאן.</p>}
        <table>
          <tbody>
            {devices?.map((d) => (
              <tr key={d.id}>
                <td><b>{d.name}</b> {d.room && <span className="muted">· {d.room}</span>}<br /><small className="muted">{d.aliases.join(', ')}</small></td>
                <td>{d.adapter}{d.sensitive && <> · <span className="badge bad">דורש אישור</span></>}{!d.enabled && ' · כבוי'}</td>
                <td>
                  {d.actions.map((a: any) => (
                    <button key={a.id} className="small ghost" onClick={async () => { try { const value = /\{\{value\}\}/.test(JSON.stringify(a)) ? prompt('ערך:') : undefined; await post(`/api/admin/home/devices/${d.id}/test`, { action: a.id, value }); setMsg(`✓ ${d.name}: ${a.label}`); } catch (e: any) { setMsg(`✗ ${e.message}`); } }}>
                      {a.label}
                    </button>
                  ))}
                </td>
                <td>
                  <button className="small" onClick={() => open(d)}>ערוך</button>
                  <button className="small ghost" onClick={async () => { if (confirm(`למחוק את ${d.name}?`)) { await del(`/api/admin/home/devices/${d.id}`); reload(); } }}>מחק</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="ok">{msg}</div>
      </Card>
      {edit && (
        <Card title={edit.id ? `עריכת ${edit.name}` : 'מכשיר חדש'} actions={<button className="ghost" onClick={() => setEdit(null)}>סגור</button>}>
          <details className="muted small">
            <summary>מבנה ההגדרה</summary>
            <pre className="mono">{`adapter: homeassistant | rest | webhook | mqtt | tablet_media
actions (לכל פעולה id + label):
  homeassistant: { "service": "light.turn_on", "data": { "entity_id": "light.salon" } }
  rest/webhook:  { "url": "...", "method": "POST", "headers": {...}, "body": "{\\"v\\":{{value}}}" }
  mqtt:          { "topic": "home/ac/set", "payload": "OFF" }
  tablet_media:  { "id": "play", "mediaUrl": "https://...stream" }, { "id": "stop" }
sensitive: true  → ג׳ארביס יבקש אישור לפני ביצוע
{{value}} מוחלף בערך שנאמר (טמפרטורה, עוצמה...)`}</pre>
          </details>
          <textarea className="mono" rows={18} value={json} onChange={(e) => setJson(e.target.value)} />
          <div className="row">
            <button
              onClick={async () => {
                try {
                  const body = JSON.parse(json);
                  if (edit.id) await put(`/api/admin/home/devices/${edit.id}`, body);
                  else await post('/api/admin/home/devices', body);
                  setEdit(null);
                  reload();
                } catch (e: any) {
                  alert(e.message);
                }
              }}
            >
              שמור מכשיר
            </button>
          </div>
        </Card>
      )}
    </>
  );
}

// ---------- tablets ----------
export function TabletsPage() {
  const [s, reload] = useLoad<any>('/api/admin/status');
  const [code, setCode] = useState('');
  if (!s) return <div>טוען…</div>;
  const online = new Set(s.online.map((d: any) => d.deviceId));
  return (
    <>
      <h2>טאבלטים</h2>
      <Card title="צימוד טאבלט חדש">
        <button onClick={async () => setCode((await post('/api/admin/devices/pairing-code')).code)}>צור קוד צימוד</button>
        {code && <div className="bigcode">{code}</div>}
        {code && <p className="muted">הקוד בתוקף ל-10 דקות ולשימוש אחד. הזן אותו במסך הצימוד בטאבלט.</p>}
      </Card>
      <Card title="מכשירים מצומדים">
        <table>
          <thead><tr><th>שם</th><th>מצב</th><th>נראה לאחרונה</th><th>מידע</th><th /></tr></thead>
          <tbody>
            {s.paired.map((d: any) => (
              <tr key={d.id} className={d.revoked ? 'revoked' : ''}>
                <td><span className={`dot ${online.has(d.id) ? 'on' : ''}`} /> {d.name}</td>
                <td>{d.revoked ? 'בוטל' : online.has(d.id) ? 'מחובר' : 'מנותק'}</td>
                <td>{fmtDate(d.last_seen_at)}<br /><small className="muted">{d.last_ip}</small></td>
                <td className="small muted">{d.info?.model ?? ''} {d.info?.android ? `Android ${d.info.android}` : ''} {d.info?.screen}</td>
                <td>
                  {!d.revoked && online.has(d.id) && (
                    <>
                      <button className="small ghost" onClick={() => post(`/api/admin/devices/${d.id}/command`, { command: 'reload' })}>טען מחדש</button>
                      <button className="small ghost" onClick={() => post(`/api/admin/devices/${d.id}/command`, { command: 'wake' })}>הער</button>
                      <button className="small ghost" onClick={() => post(`/api/admin/devices/${d.id}/command`, { command: 'slideshow' })}>מצגת</button>
                    </>
                  )}
                  {!d.revoked && <button className="small ghost" onClick={async () => { if (confirm('לבטל את הגישה של המכשיר?')) { await post(`/api/admin/devices/${d.id}/revoke`); reload(); } }}>בטל גישה</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </>
  );
}

// ---------- history ----------
export function HistoryPage() {
  const [convs] = useLoad<any[]>('/api/admin/conversations');
  const [actions] = useLoad<any[]>('/api/admin/actions');
  const [sel, setSel] = useState<number | null>(null);
  const [msgs, setMsgs] = useState<any[]>([]);
  useEffect(() => {
    if (sel) adm(`/api/admin/conversations/${sel}`).then(setMsgs);
  }, [sel]);
  return (
    <>
      <h2>היסטוריה ופעולות</h2>
      <div className="grid2 top">
        <Card title="שיחות">
          <ul className="list">
            {convs?.map((c) => (
              <li key={c.id} className={sel === c.id ? 'on' : ''} onClick={() => setSel(Number(c.id))}>
                <b>{c.first ?? '—'}</b>
                <small className="muted">{fmtDate(c.last_at)} · {c.n} הודעות · {c.device ?? 'ניהול'}</small>
              </li>
            ))}
          </ul>
        </Card>
        <Card title="תמלול">
          {!sel && <p className="muted">בחר שיחה.</p>}
          {msgs.map((m) => (
            <div key={m.id} className={`msg ${m.role}`}>
              {m.role === 'tool' ? <details><summary>🔧 {m.content.name}</summary><pre className="mono">{m.content.content.slice(0, 2000)}</pre></details> : m.content.content}
              {m.content.toolCalls?.map((t: any) => <div key={t.id} className="small muted">→ {t.name}({JSON.stringify(t.args)})</div>)}
            </div>
          ))}
        </Card>
      </div>
      <Card title="יומן פעולות">
        <table className="compact">
          <tbody>
            {actions?.map((a) => (
              <tr key={a.id}>
                <td>{fmtDate(a.ts)}</td>
                <td><span className={`badge ${a.status === 'ok' ? 'good' : a.status === 'pending' ? '' : 'bad'}`}>{a.status}</span></td>
                <td>{a.type}</td>
                <td>{a.summary}</td>
                <td className="muted small">{a.source}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </>
  );
}

// ---------- usage ----------
export function UsagePage() {
  const [days, setDays] = useState(30);
  const [u] = useLoad<any>(`/api/admin/usage?days=${days}`, [days]);
  if (!u) return <div>טוען…</div>;
  const perDay: Record<string, number> = {};
  u.byDay.forEach((r: any) => (perDay[r.day] = (perDay[r.day] ?? 0) + r.cost));
  const max = Math.max(0.0001, ...Object.values(perDay));
  return (
    <>
      <h2>שימוש ועלויות</h2>
      <div className="grid4">
        <div className="stat"><b>{usd(u.day)}</b><span>היום</span></div>
        <div className="stat"><b>{usd(u.month)}</b><span>החודש</span></div>
      </div>
      <Card title="עלות יומית" actions={<select value={days} onChange={(e) => setDays(Number(e.target.value))}><option value={7}>7 ימים</option><option value={30}>30 ימים</option><option value={90}>90 ימים</option></select>}>
        <div className="bars">
          {Object.entries(perDay).map(([d, c]) => (
            <div key={d} className="bar" title={`${d}: ${usd(c)}`}>
              <i style={{ height: `${(c / max) * 100}%` }} />
              <span>{d.slice(8)}</span>
            </div>
          ))}
        </div>
      </Card>
      <Card title="לפי מודל">
        <table>
          <thead><tr><th>ספק</th><th>מודל</th><th>סוג</th><th>קריאות</th><th>קלט</th><th>פלט</th><th>עלות</th></tr></thead>
          <tbody>
            {u.byModel.map((r: any, i: number) => (
              <tr key={i}>
                <td>{r.provider}</td><td className="mono">{r.model}</td><td>{r.kind}</td><td>{r.calls}</td>
                <td>{Math.round(r.input).toLocaleString()} {r.kind === 'llm' ? 'טוק׳' : r.kind === 'stt' ? 'שנ׳' : 'תווים'}</td>
                <td>{Math.round(r.output).toLocaleString()}</td><td>{usd(r.cost)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </>
  );
}

// ---------- errors ----------
export function ErrorsPage() {
  const [errs, reload] = useLoad<any[]>('/api/admin/errors');
  return (
    <>
      <h2>שגיאות מערכת</h2>
      <Card title={`${errs?.length ?? 0} אחרונות`} actions={<><button className="ghost" onClick={reload}>רענן</button><button className="ghost" onClick={async () => { if (confirm('לנקות את כל השגיאות?')) { await del('/api/admin/errors'); reload(); } }}>נקה</button></>}>
        <table className="compact">
          <tbody>
            {errs?.map((e) => (
              <tr key={e.id}>
                <td>{fmtDate(e.ts)}</td>
                <td className="mono">{e.source}</td>
                <td>{e.message}<details><summary className="muted small">פרטים</summary><pre className="mono">{JSON.stringify(e.detail, null, 1)}</pre></details></td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </>
  );
}

// ---------- security ----------
export function SecurityPage() {
  const [cur, setCur] = useState('');
  const [next, setNext] = useState('');
  const [msg, setMsg] = useState('');
  return (
    <>
      <h2>אבטחה</h2>
      <Card title="החלפת סיסמה">
        <Field label="סיסמה נוכחית"><input type="password" value={cur} onChange={(e) => setCur(e.target.value)} /></Field>
        <Field label="סיסמה חדשה (10 תווים לפחות)"><input type="password" value={next} onChange={(e) => setNext(e.target.value)} /></Field>
        <button onClick={async () => { try { await post('/api/admin/password', { current: cur, next }); setMsg('הסיסמה עודכנה. שאר החיבורים נותקו.'); setCur(''); setNext(''); } catch (e: any) { setMsg(e.message); } }}>עדכן</button>
        <span className="ok"> {msg}</span>
      </Card>
      <Card title="מדיניות">
        <ul className="muted">
          <li>מפתחות API וטוקני OAuth מוצפנים ב-AES-256-GCM במסד הנתונים; מפתח ההצפנה נמצא רק בקובץ ‎.env בשרת.</li>
          <li>הטאבלט מזדהה עם טוקן אישי (נוצר בצימוד), שניתן לבטל בכל רגע.</li>
          <li>תוכן ממיילים, מסמכים והזמנות ביומן מסומן כלא-מהימן. אחרי שתוכן כזה נקרא, כל פעולה בעולם האמיתי באותה בקשה דורשת אישור מפורש.</li>
          <li>פעולות רגישות (יצירת אירוע, מכשירים שסומנו "דורש אישור") מתבצעות רק אחרי "כן" מפורש ממך או לחיצה על "אשר" — ההחלטה מתקבלת בקוד, לא על ידי המודל.</li>
          <li>ממשק הניהול: עוגיית HttpOnly/SameSite=Strict, הגנת CSRF, הגבלת ניסיונות כניסה.</li>
        </ul>
      </Card>
    </>
  );
}
