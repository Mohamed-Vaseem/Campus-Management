import { useEffect, useState } from 'react';
import { api } from './api';
import Summary from './Summary';

export default function Admin() {
  const [depts, setDepts] = useState([]);
  const [users, setUsers] = useState([]);
  const [audit, setAudit] = useState([]);
  const [msg, setMsg] = useState('');
  const [d, setD] = useState({ code: '', name: '' });
  const [u, setU] = useState({ role: 'STAFF', departmentId: '', staffCode: '', name: '', email: '', designation: '', password: '' });

  const load = () => Promise.all([api('/admin/departments').then(setDepts), api('/admin/users').then(setUsers), api('/admin/audit?limit=30').then(setAudit)]);
  useEffect(() => { load(); }, []);
  const run = (p) => p.then(() => { setMsg(''); return load(); }).catch((x) => setMsg(x.message));
  const deptName = (id) => depts.find((x) => x.id === id)?.code || '–';

  return (
    <>
      {msg && <p className="err">{msg}</p>}
      <section className="card">
        <h2>🏛️ Departments</h2>
        <form className="row" onSubmit={(e) => { e.preventDefault(); run(api('/admin/departments', { method: 'POST', body: d }).then(() => setD({ code: '', name: '' }))); }}>
          <input placeholder="Code (CSE)" value={d.code} onChange={(e) => setD({ ...d, code: e.target.value })} required maxLength={16} />
          <input placeholder="Name" value={d.name} onChange={(e) => setD({ ...d, name: e.target.value })} required />
          <button>Create</button>
        </form>
        <table><tbody>{depts.map((x) => (
          <tr key={x.id}><td>{x.code}</td><td>{x.name}</td><td><span className={'chip ' + (x.active ? 'ON' : 'OFF')}>{x.active ? 'Active' : 'Inactive'}</span></td>
            <td><button onClick={() => run(api(`/admin/departments/${x.id}`, { method: 'PATCH', body: { active: !x.active } }))}>{x.active ? 'Deactivate' : 'Activate'}</button></td></tr>))}</tbody></table>
      </section>

      <section className="card">
        <h2>➕ Register user</h2>
        <form className="row" onSubmit={(e) => { e.preventDefault(); run(api('/admin/users', { method: 'POST', body: { ...u, departmentId: u.departmentId || null } }).then(() => setU({ ...u, name: '', email: '', staffCode: '', designation: '', password: '' }))); }}>
          <select value={u.role} onChange={(e) => setU({ ...u, role: e.target.value })}>
            {['STAFF', 'HOD', 'MANAGEMENT', 'SUPER_ADMIN'].map((r) => <option key={r}>{r}</option>)}
          </select>
          <select value={u.departmentId} onChange={(e) => setU({ ...u, departmentId: e.target.value })} required={['STAFF', 'HOD'].includes(u.role)}>
            <option value="">Department…</option>{depts.filter((x) => x.active).map((x) => <option key={x.id} value={x.id}>{x.code}</option>)}
          </select>
          <input placeholder="Staff ID" value={u.staffCode} onChange={(e) => setU({ ...u, staffCode: e.target.value })} />
          <input placeholder="Name" value={u.name} onChange={(e) => setU({ ...u, name: e.target.value })} required />
          <input type="email" placeholder="Official email" value={u.email} onChange={(e) => setU({ ...u, email: e.target.value })} required />
          <input placeholder="Designation" value={u.designation} onChange={(e) => setU({ ...u, designation: e.target.value })} />
          <input type="password" placeholder="Initial password (10+)" minLength={10} value={u.password} onChange={(e) => setU({ ...u, password: e.target.value })} required autoComplete="new-password" />
          <button>Register</button>
        </form>
      </section>

      <section className="card">
        <h2>👤 Users</h2>
        <div className="scroll"><table>
          <thead><tr><th>Name</th><th>Role</th><th>Dept</th><th>Status</th><th></th></tr></thead>
          <tbody>{users.map((x) => (
            <tr key={x.id}><td>{x.name}<br /><span className="muted">{x.email}</span></td><td><span className="chip">{x.role}</span></td><td>{deptName(x.department_id)}</td><td><span className={'chip ' + (x.active ? 'ON' : 'OFF')}>{x.active ? 'Active' : 'Inactive'}</span></td>
              <td className="row">
                <button onClick={() => run(api(`/admin/users/${x.id}`, { method: 'PATCH', body: { active: !x.active } }))}>{x.active ? 'Deactivate' : 'Activate'}</button>
                {x.role === 'STAFF' && <>
                  <button onClick={() => run(api(`/admin/departments/${x.department_id}/hod`, { method: 'POST', body: { userId: x.id } }))}>Make HOD</button>
                  <select defaultValue="" onChange={(e) => e.target.value && run(api(`/admin/users/${x.id}/transfer`, { method: 'POST', body: { departmentId: e.target.value } }))}>
                    <option value="">Transfer to…</option>{depts.filter((y) => y.active && y.id !== x.department_id).map((y) => <option key={y.id} value={y.id}>{y.code}</option>)}
                  </select></>}
              </td></tr>))}</tbody>
        </table></div>
      </section>

      <Summary />

      <section className="card">
        <h2>🧾 Audit log</h2>
        <div className="scroll"><table><tbody>{audit.map((a) => (
          <tr key={a.id}><td>{new Date(a.at).toLocaleString()}</td><td>{a.action}</td><td>{deptName(a.department_id)}</td><td className="muted">{a.detail}</td></tr>))}</tbody></table></div>
      </section>
    </>
  );
}
