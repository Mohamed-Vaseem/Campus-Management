import { useEffect, useState } from 'react';
import { api, ago } from './api';

const RESPONSES = [
  ['ACKNOWLEDGE', 'Acknowledge'], ['ON_MY_WAY', 'On my way'], ['BUSY_TEACHING', 'Busy teaching'],
  ['REQUEST_MORE_TIME', 'Need more time'], ['UNABLE_TO_ATTEND', 'Unable to attend'],
];
const DAYS = ['', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

export default function Staff({ me, evt }) {
  const [status, setStatus] = useState({ location: '', activity: '', availability: 'AVAILABLE' });
  const [saved, setSaved] = useState(null);
  const [locs, setLocs] = useState([]);
  const [notes, setNotes] = useState([]);
  const [tt, setTt] = useState([]);
  const [msg, setMsg] = useState('');

  const loadNotes = async () => {
    const list = await api('/notifications?limit=30');
    setNotes(list);
    list.filter((n) => n.status === 'PENDING').forEach((n) => api(`/notifications/${n.id}/delivered`, { method: 'POST' }).catch(() => {}));
  };
  useEffect(() => {
    api('/status/me').then((s) => { if (s.location) { setStatus(s); setSaved(s.updated_at); } });
    api('/locations').then(setLocs);
    api('/timetable').then(setTt);
    loadNotes();
  }, []);
  useEffect(() => { if (evt && evt.ev.startsWith('notification')) loadNotes(); }, [evt]);

  async function save(e) {
    e.preventDefault();
    try {
      const r = await api('/status/me', { method: 'PUT', body: { location: status.location, activity: status.activity, availability: status.availability } });
      setSaved(r.updated_at); setMsg('Updated');
    } catch (x) { setMsg(x.message); }
  }
  const respond = (id, response) => api(`/notifications/${id}/respond`, { method: 'POST', body: { response } }).then(loadNotes).catch((x) => setMsg(x.message));

  return (
    <>
      <section className="card">
        <h2>📍 My location & availability</h2>
        <form onSubmit={save} className="row">
          <input list="locs" placeholder="Location (e.g. Classroom C-204)" value={status.location} onChange={(e) => setStatus({ ...status, location: e.target.value })} required maxLength={120} />
          <datalist id="locs">{locs.map((l) => <option key={l.id} value={l.name} />)}</datalist>
          <input placeholder="Activity (e.g. Teaching)" value={status.activity} onChange={(e) => setStatus({ ...status, activity: e.target.value })} required maxLength={120} />
          <select value={status.availability} onChange={(e) => setStatus({ ...status, availability: e.target.value })}>
            <option value="AVAILABLE">Available</option><option value="BUSY">Busy</option>
            <option value="IN_CLASS">In class</option><option value="AWAY">Away</option>
          </select>
          <button>Update</button>
        </form>
        <p className="muted">Last updated: {saved ? ago(saved) : 'never'} {msg && `· ${msg}`}</p>
      </section>

      <section className="card">
        <h2>🔔 HOD requests</h2>
        {notes.length === 0 && <p className="muted">No requests.</p>}
        {notes.map((n) => (
          <div key={n.id} className={'note ' + (['PENDING', 'DELIVERED', 'ESCALATED'].includes(n.status) ? 'open' : '')}>
            <div><strong>{n.sender_name}</strong> · <span className="badge">{n.urgency}</span> · {ago(n.created_at)}</div>
            <p>{n.message}</p>
            {['PENDING', 'DELIVERED', 'ESCALATED', 'ACKNOWLEDGED'].includes(n.status)
              ? <div className="row">{RESPONSES.map(([k, l]) => <button key={k} onClick={() => respond(n.id, k)}>{l}</button>)}</div>
              : <span className="muted">Status: {n.status}{n.response ? ` (${n.response.replaceAll('_', ' ').toLowerCase()})` : ''}</span>}
          </div>
        ))}
      </section>

      <section className="card">
        <h2>🗓️ My timetable</h2>
        {tt.length === 0 ? <p className="muted">No entries.</p> :
          <table><tbody>{tt.map((t) => <tr key={t.id}><td>{DAYS[t.day_of_week]}</td><td>{t.start_time}–{t.end_time}</td><td>{t.subject}</td><td>{t.location}</td></tr>)}</tbody></table>}
      </section>
    </>
  );
}
