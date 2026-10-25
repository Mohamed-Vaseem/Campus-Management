import { useEffect, useState } from 'react';
import { api } from './api';

export default function Summary({ refresh }) {
  const [rows, setRows] = useState([]);
  useEffect(() => { api('/reports/summary').then(setRows).catch(() => {}); }, [refresh]);
  const sum = (k) => rows.reduce((a, r) => a + Number(r[k] || 0), 0);
  const tiles = [['👥', 'Staff', 'staff_count'], ['📨', 'Calls', 'calls_total'], ['⏳', 'Open', 'calls_open'], ['✅', 'Answered', 'calls_answered'], ['🕒', 'Stale locations', 'stale_staff']];
  return (
    <section className="card">
      <h2>📊 Reports</h2>
      <div className="tiles">{tiles.map(([i, l, k]) => <div className="tile" key={k}><span>{i} {l}</span><b>{sum(k)}</b></div>)}</div>
      <div className="scroll"><table>
        <thead><tr><th>Dept</th><th>Staff</th><th>Calls</th><th>Open</th><th>Answered</th><th>Avg response</th><th>Stale</th></tr></thead>
        <tbody>{rows.map((r) => (
          <tr key={r.department_id}>
            <td><span className="chip">{r.code}</span></td><td>{r.staff_count}</td><td>{r.calls_total}</td><td>{r.calls_open}</td><td>{r.calls_answered}</td>
            <td>{r.avg_response_seconds == null ? '–' : Math.round(r.avg_response_seconds) + ' s'}</td><td>{r.stale_staff}</td>
          </tr>))}</tbody>
      </table></div>
    </section>
  );
}
