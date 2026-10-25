import { useEffect, useState } from 'react';
import { api, hasToken, setToken, openStream } from './api';
import Staff from './Staff';
import Hod from './Hod';
import Admin from './Admin';
import Summary from './Summary';

function Login({ onDone }) {
  const [email, setEmail] = useState('');
  const [pw, setPw] = useState('');
  const [err, setErr] = useState('');
  async function submit(e) {
    e.preventDefault();
    try { const r = await api('/auth/login', { method: 'POST', body: { email, password: pw } }); setToken(r.token); onDone(); }
    catch (x) { setErr(x.message); }
  }
  return (
    <div className="auth">
      <div className="hero">
        <div className="logo big">SC</div>
        <h1>Staff Coordination</h1>
        <p>One college platform, every department independent. Call staff in one tap and see who is where.</p>
        <ul>
          <li>📍 Live staff location &amp; availability</li>
          <li>🔔 Instant HOD calling with quick responses</li>
          <li>🔒 Strict department data separation</li>
        </ul>
      </div>
      <form className="card login" onSubmit={submit}>
        <h2>Welcome back</h2>
        <span className="muted">Sign in to continue</span>
        <label>Username / email
          <input type="text" inputMode="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" required />
        </label>
        <label>Password
          <input type="password" value={pw} onChange={(e) => setPw(e.target.value)} autoComplete="current-password" required />
        </label>
        {err && <p className="err">{err}</p>}
        <button>Sign in</button>
      </form>
    </div>
  );
}

export default function App() {
  const [me, setMe] = useState(null);
  const [evt, setEvt] = useState(null);
  const [loading, setLoading] = useState(hasToken());

  const load = () => api('/me').then(setMe).catch(() => setToken(null)).finally(() => setLoading(false));
  useEffect(() => { if (hasToken()) load(); }, []);

  useEffect(() => {
    if (!me) return;
    if ('Notification' in window && Notification.permission === 'default') Notification.requestPermission();
    return openStream((ev, data) => {
      if (ev === 'auth') return logout();
      setEvt({ ev, data, n: Date.now() });
      if (ev === 'notification' && 'Notification' in window && Notification.permission === 'granted')
        new Notification(data.sender_name + ' is calling you', { body: data.message });
    });
  }, [me?.id]);

  function logout() { setToken(null); setMe(null); }

  if (loading) return <p className="muted pad">Loading…</p>;
  if (!me) return <Login onDone={load} />;
  return (
    <>
      <header>
        <div className="brand"><span className="logo">SC</span>Staff Coordination</div>
        <div className="who-am">
          <span className="avatar">{me.name[0]}</span>
          <span>{me.name} <span className="chip">{me.role.replace('_', ' ')}{me.department_code ? ` · ${me.department_code}` : ''}</span></span>
          <button className="ghost" onClick={logout}>Sign out</button>
        </div>
      </header>
      <main>
        {me.role === 'STAFF' && <Staff me={me} evt={evt} />}
        {me.role === 'HOD' && <Hod me={me} evt={evt} />}
        {me.role === 'SUPER_ADMIN' && <Admin evt={evt} />}
        {me.role === 'MANAGEMENT' && <Summary />}
      </main>
    </>
  );
}
