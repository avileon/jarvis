import { useEffect, useState } from 'react';
import { adm, post } from './api';
import * as P from './pages';
import './admin.css';

const PAGES = [
  { id: '', label: 'לוח בקרה', C: P.Dashboard },
  { id: 'ai', label: 'בינה מלאכותית', C: P.AiPage },
  { id: 'voice', label: 'קול והאזנה', C: P.VoicePage },
  { id: 'google', label: 'Google ותמונות', C: P.GooglePage },
  { id: 'home', label: 'בית חכם', C: P.HomePage },
  { id: 'tablets', label: 'טאבלטים', C: P.TabletsPage },
  { id: 'history', label: 'היסטוריה ופעולות', C: P.HistoryPage },
  { id: 'usage', label: 'שימוש ועלויות', C: P.UsagePage },
  { id: 'errors', label: 'שגיאות', C: P.ErrorsPage },
  { id: 'security', label: 'אבטחה', C: P.SecurityPage },
];

function currentPage() {
  return location.pathname.replace(/^\/admin\/?/, '').split('/')[0] ?? '';
}

export default function Admin() {
  const [me, setMe] = useState<string | null | undefined>(undefined);
  const [page, setPage] = useState(currentPage());
  const [events, setEvents] = useState<any[]>([]);

  useEffect(() => {
    adm('/api/admin/me').then((r) => setMe(r.username)).catch(() => setMe(null));
    const onPop = () => setPage(currentPage());
    window.addEventListener('popstate', onPop);
    return () => window.removeEventListener('popstate', onPop);
  }, []);

  useEffect(() => {
    if (!me) return;
    let ws: WebSocket | null = null;
    let t: any;
    const connect = () => {
      ws = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/admin`);
      ws.onmessage = (e) => setEvents((ev) => [JSON.parse(e.data), ...ev].slice(0, 30));
      ws.onclose = () => (t = setTimeout(connect, 5000));
    };
    connect();
    return () => {
      clearTimeout(t);
      if (ws) {
        ws.onclose = null;
        ws.close();
      }
    };
  }, [me]);

  if (me === undefined) return <div className="adm-loading">טוען…</div>;
  if (me === null) return <Login onLogin={(u) => setMe(u)} />;

  const go = (id: string) => {
    history.pushState(null, '', `/admin/${id}`);
    setPage(id);
  };
  const Page = PAGES.find((p) => p.id === page)?.C ?? P.Dashboard;

  return (
    <div className="adm">
      <aside>
        <div className="logo">J A R V I S<small>ממשק ניהול</small></div>
        <nav>
          {PAGES.map((p) => (
            <a key={p.id} className={p.id === page ? 'on' : ''} onClick={() => go(p.id)}>
              {p.label}
            </a>
          ))}
        </nav>
        <div className="who">
          {me} ·{' '}
          <a onClick={async () => { await post('/api/admin/logout'); location.reload(); }}>יציאה</a>
          <br />
          <a href="/" target="_blank" rel="noreferrer">פתח תחנה ↗</a>
        </div>
      </aside>
      <section className="content">
        <Page events={events} />
      </section>
    </div>
  );
}

function Login({ onLogin }: { onLogin: (u: string) => void }) {
  const [u, setU] = useState('avi');
  const [p, setP] = useState('');
  const [err, setErr] = useState('');
  return (
    <form
      className="login"
      onSubmit={async (e) => {
        e.preventDefault();
        setErr('');
        try {
          await post('/api/admin/login', { username: u, password: p });
          onLogin(u);
        } catch (e: any) {
          setErr(e.message);
        }
      }}
    >
      <div className="logo">J A R V I S<small>כניסה לממשק הניהול</small></div>
      <input value={u} onChange={(e) => setU(e.target.value)} placeholder="שם משתמש" autoComplete="username" />
      <input value={p} onChange={(e) => setP(e.target.value)} type="password" placeholder="סיסמה" autoComplete="current-password" />
      <button>כניסה</button>
      {err && <div className="err">{err}</div>}
    </form>
  );
}
