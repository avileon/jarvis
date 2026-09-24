# JARVIS — Personal AI Assistant

עוזר אישי קולי בעברית: שרת AI + ממשק כדור אנרגיה לטאבלט + מצגת תמונות + בית חכם.

```
Tablet (Android shell → WebView)          Server (Docker Compose)
┌──────────────────────────────┐          ┌────────────────────────────────────────────┐
│ Orb UI / Slideshow (React)   │  HTTPS   │ Caddy (Let's Encrypt)                      │
│ openWakeWord "hey jarvis"    │◄────────►│ jarvis: Node 22 + Fastify + WebSocket       │
│  (on-device, no idle audio)  │   WSS    │  ├ AI: Anthropic / OpenAI (tool calling)   │
│ VAD → WAV upload after wake  │          │  ├ STT gpt-4o-transcribe · TTS Azure Avri  │
│ Cache Storage: photos        │          │  ├ Google: Gmail · Calendar · Drive photos │
└──────────────────────────────┘          │  ├ Smart home: HA / REST / Webhook / MQTT  │
                                          │  └ Admin panel (/admin)                    │
                                          │ PostgreSQL 16                               │
                                          └────────────────────────────────────────────┘
```

## Layout
| Path | |
|---|---|
| `server/` | TypeScript backend. `src/ai` agent loop + providers, `src/voice` STT/TTS, `src/google`, `src/home`, `src/tools` (functions the model may call), `src/routes` |
| `web/` | React app: `/` = tablet station, `/admin` = admin panel. `public/wakeword` = openWakeWord ONNX models |
| `android/` | Kotlin WebView kiosk shell (fullscreen, mic, keep-screen-on while charging, reconnect, boot start) |
| `deploy/` | `docker-compose.yml`, `Caddyfile`, `install.sh` (bootstrap), `update.sh`, PM2 alternative |

## Install (fresh Ubuntu server)
```bash
curl -fsSL -H "Authorization: token <read-only token>" \
  https://raw.githubusercontent.com/avileon/jarvis/main/deploy/install.sh -o install.sh
sudo bash install.sh
```
The script prints the environment + plan and waits for confirmation. DNS: `A jarvis.vibit.co.il → server IP`.

Update: `bash /opt/jarvis/deploy/update.sh` — the tablet reloads the new UI by itself.

Without Docker: Node 22 + PostgreSQL + PM2 — see `deploy/ecosystem.config.cjs` (add `DATABASE_URL` and `PUBLIC_URL` to `deploy/.env`).

## First-time setup (admin panel)
1. `https://jarvis.vibit.co.il/admin` → login (password printed by install.sh).
2. **בינה מלאכותית**: Anthropic + OpenAI keys, choose provider/model, set spending caps.
3. **קול והאזנה**: Azure Speech key + region → "השמע" to test the voice.
4. **Google ותמונות**: OAuth client (redirect URI shown on the page) → connect → Drive folder link.
5. **טאבלטים**: create pairing code → enter it on the tablet.
6. **בית חכם**: Home Assistant URL + token (import entities), or REST/Webhook/MQTT devices, or `tablet_media` radio streams.

## Security model
- API keys and OAuth tokens: AES-256-GCM in Postgres; key only in `deploy/.env` (`JARVIS_MASTER_KEY`). Never sent to the tablet.
- Tablet auth: per-device token from a one-time 6-digit pairing code; revocable. WebSocket authenticates in the first message (token never in URLs).
- Admin: scrypt password, HttpOnly SameSite=Strict session cookie, CSRF header, login rate limit, CSP.
- Prompt-injection defence: email/calendar/document content is wrapped as `<untrusted_content>`; once such content enters a turn, **every real-world action in that turn requires explicit confirmation**. Sensitive tools (create event, devices flagged `sensitive`) always require it. The "yes/no" is parsed by code, not by the model.
- Wake word runs on the tablet; audio is uploaded only after "hey jarvis" (or a tap).

## Development
```bash
npm install
# Postgres on localhost with db jarvis / jarvis_test (user jarvis:jarvis)
cp deploy/.env.example server/.env   # set DATABASE_URL, PUBLIC_URL=http://localhost:3000
npm run dev:server   # :3000
npm run dev:web      # :5173 (proxies /api, /ws)
npm test -w server   # integration tests with mocked AI/STT/TTS upstreams
```

## Android APK
Built by GitHub Actions (`.github/workflows/android.yml` → Run workflow; inputs: server URL, minSdk).
The APK is a thin shell — UI/logic updates come from the server. Hidden settings: 5 quick taps top-left.
Auto-start after boot: set JARVIS as the Home app (most reliable), or grant "display over other apps".

## Licences / notes
- openWakeWord pre-trained models (`hey_jarvis`): CC BY-NC-SA 4.0 — personal, non-commercial use.
- Voice: Azure `he-IL-AvriNeural` — an original synthetic voice, not an imitation of any actor.
- Pricing table in the admin panel is editable; verify against provider price pages.
