import { useEffect, useState } from 'react';
import { api, ago } from './api';
import Summary from './Summary';

const DAYS = ['', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

export default function Hod({ me, evt }) {
  const [staff, setStaff] = useState([]);
  const [notes, setNotes] = useState([]);
  const [locs, setLocs] = useState([]);
  const [tt, setTt] = useState([]);
  const [q, setQ] = useState('');
  const [urgency, setUrgency] = useState('IMMEDIATE');
  const [msg, setMsg] = useState('');
  const [loc, setLoc] = useState({ name: '', kind: 'CLASSROOM' });
  const [row, setRow] = useState({ userId: '', dayOfWeek: 1, startTime: '09:00', endTime: '10:00', subject: '', location: '' });

  const loadStaff = () => api('/staff').then(setStaff);
  const loadNotes = () => api('/notifications?limit=50').then(setNotes);
  const loadLocs = () => api('/locations').then(setLocs);
  const loadTt = () => api('/timetable').then(setTt);
  useEffect(() => { loadStaff(); loadNotes(); loadLocs(); loadTt(); }, []);
  useEffect(() => {
    if (!evt) return;
    if (evt.ev === 'status') loadStaff();
    else loadNotes();
  }, [evt]);

  const guard = (p) => p.then(() => setMsg('')).catch((x) => setMsg(x.message));
  const call = (s) => guard(api('/notifications', { method: 'POST', body: { recipientId: s.id, urgency } }).then((n) => { setMsg(`Called ${n.recipient_name}`); return loadNotes(); }));
  const act = (id, what) => guard(api(`/notifications/${id}/${what}`, { method: 'POST' }).then(loadNotes));
  const nameOf = (id) => staff.find((s) => s.id === id)?.name || '';
  const shown = staff.filter((s) => s.name.toLowerCase().includes(q.toLowerCase()) || (s.staff_code || '').toLowerCase().includes(q.toLowerCase()));

  return (
    <>
      <section className="card">
        <h2>👥 {me.department_name} — staff</h2>
        <div className="row">
          <input placeholder="Search staff…" value={q} onChange={(e) => setQ(e.target.value)} />
          <select value={urgency} onChange={(e) => setUrgency(e.target.value)}>
            <option value="IMMEDIATE">Immediate</option><option value="SOON">Soon</option><option value="ROUTINE">Routine</option>
          </select>
          {msg && <span className="muted">{msg}</span>}
        </div>
        <div className="scroll"><table>
          <thead><tr><th>Name</th><th>Where / doing</th><th>Availability</th><th>Reported</th><th></th></tr></thead>
          <tbody>{shown.map((s) => (
            <tr key={s.id}>
              <td><div className="who"><span className="avatar">{s.name[0]}</span><div>{s.name}<br /><span className="muted">{s.designation}</span></div></div></td>
              <td>{s.location ? `${s.location} — ${s.activity}` : <span className="muted">No report yet</span>}</td>
              <td>{s.availability ? <span className={'chip ' + s.availability}>{s.availability.replace('_', ' ')}</span> : '–'}</td>
              <td>{ago(s.updated_at)} {s.stale && <span className="badge warn">stale</span>}</td>
              <td><button onClick={() => call(s)}>📞 Call Staff</button></td>
            </tr>))}</tbody>
        </table></div>
      </section>

      <section className="card">
        <h2>📨 Requests</h2>
        <div className="scroll"><table>
          <thead><tr><th>Staff</th><th>Sent</th><th>Status</th><th>Response</th><th></th></tr></thead>
          <tbody>{notes.map((n) => (
            <tr key={n.id}>
              <td>{n.recipient_name}</td><td>{ago(n.created_at)}</td>
              <td><span className={'chip ' + n.status}>{n.status}</span></td>
              <td>{n.response ? n.response.replaceAll('_', ' ').toLowerCase() : '–'}</td>
              <td>
                {['PENDING', 'DELIVERED'].includes(n.status) && <button onClick={() => act(n.id, 'escalate')}>Escalate</button>}
                {['ACKNOWLEDGED', 'RESPONDED', 'ESCALATED'].includes(n.status) && <button onClick={() => act(n.id, 'complete')}>Complete</button>}
              </td>
            </tr>))}</tbody>
        </table></div>
      </section>

      <Summary refresh={notes.length} />

      <section className="card">
        <h2>🏫 Locations</h2>
        <form className="row" onSubmit={(e) => { e.preventDefault(); guard(api('/locations', { method: 'POST', body: loc }).then(() => { setLoc({ ...loc, name: '' }); return loadLocs(); })); }}>
          <input placeholder="Room / lab name" value={loc.name} onChange={(e) => setLoc({ ...loc, name: e.target.value })} required maxLength={80} />
          <select value={loc.kind} onChange={(e) => setLoc({ ...loc, kind: e.target.value })}>
            {['CLASSROOM', 'LAB', 'OFFICE', 'WORKSHOP', 'OTHER'].map((k) => <option key={k}>{k}</option>)}
          </select>
          <button>Add</button>
        </form>
        <p>{locs.map((l) => <span key={l.id} className="badge">{l.name} <a href="#" onClick={(e) => { e.preventDefault(); guard(api(`/locations/${l.id}`, { method: 'DELETE' }).then(loadLocs)); }}>×</a></span>)}</p>
      </section>

      <section className="card">
        <h2>🗓️ Timetable</h2>
        <form className="row" onSubmit={(e) => { e.preventDefault(); guard(api('/timetable', { method: 'POST', body: { ...row, dayOfWeek: Number(row.dayOfWeek) } }).then(loadTt)); }}>
          <select value={row.userId} onChange={(e) => setRow({ ...row, userId: e.target.value })} required>
            <option value="">Staff…</option>{staff.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
          <select value={row.dayOfWeek} onChange={(e) => setRow({ ...row, dayOfWeek: e.target.value })}>
            {DAYS.slice(1).map((d, i) => <option key={d} value={i + 1}>{d}</option>)}
          </select>
          <input type="time" value={row.startTime} onChange={(e) => setRow({ ...row, startTime: e.target.value })} required />
          <input type="time" value={row.endTime} onChange={(e) => setRow({ ...row, endTime: e.target.value })} required />
          <input placeholder="Subject" value={row.subject} onChange={(e) => setRow({ ...row, subject: e.target.value })} required maxLength={120} />
          <input list="hloc" placeholder="Location" value={row.location} onChange={(e) => setRow({ ...row, location: e.target.value })} required maxLength={120} />
          <datalist id="hloc">{locs.map((l) => <option key={l.id} value={l.name} />)}</datalist>
          <button>Add</button>
        </form>
        <div className="scroll"><table><tbody>{tt.map((t) => (
          <tr key={t.id}><td>{nameOf(t.user_id)}</td><td>{DAYS[t.day_of_week]}</td><td>{t.start_time}–{t.end_time}</td><td>{t.subject}</td><td>{t.location}</td>
            <td><a href="#" onClick={(e) => { e.preventDefault(); guard(api(`/timetable/${t.id}`, { method: 'DELETE' }).then(loadTt)); }}>remove</a></td></tr>))}</tbody></table></div>
      </section>
    </>
  );
}
