const BASE = import.meta.env.VITE_API || '/api';
let token = sessionStorage.getItem('tok');

export const hasToken = () => !!token;
export function setToken(t) {
  token = t;
  if (t) sessionStorage.setItem('tok', t); else sessionStorage.removeItem('tok');
}

export async function api(path, { method = 'GET', body } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (res.status === 401 && token) { setToken(null); window.location.reload(); }
  if (!res.ok) {
    const e = new Error(res.status === 403 ? 'Not permitted' : res.status === 404 ? 'Not found' :
      res.status === 409 ? 'Conflict or invalid data' : res.status === 429 ? 'Too many attempts, try later' :
      res.status === 401 ? 'Invalid credentials' : 'Request failed');
    e.status = res.status;
    throw e;
  }
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

/** Authenticated Server-Sent Events via fetch (EventSource cannot send an Authorization header). */
export function openStream(onEvent) {
  const ctrl = new AbortController();
  let stop = false;
  (async function loop() {
    while (!stop) {
      try {
        const r = await fetch(BASE + '/stream', { headers: { Authorization: 'Bearer ' + token }, signal: ctrl.signal });
        if (r.status === 401) { onEvent('auth', null); return; }
        const reader = r.body.getReader();
        const dec = new TextDecoder();
        let buf = '';
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          buf += dec.decode(value, { stream: true });
          let i;
          while ((i = buf.indexOf('\n\n')) >= 0) {
            const block = buf.slice(0, i); buf = buf.slice(i + 2);
            let ev = 'message', data = '';
            for (const line of block.split('\n')) {
              if (line.startsWith('event:')) ev = line.slice(6).trim();
              else if (line.startsWith('data:')) data += line.slice(5).trim();
            }
            if (data) { try { onEvent(ev, JSON.parse(data)); } catch { /* ignore malformed */ } }
          }
        }
      } catch { if (stop) return; }
      await new Promise((r) => setTimeout(r, 3000));
    }
  })();
  return () => { stop = true; ctrl.abort(); };
}

export const ago = (iso) => {
  if (!iso) return 'never';
  const m = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
  return m < 1 ? 'just now' : m < 60 ? `${m} min ago` : m < 1440 ? `${Math.round(m / 60)} h ago` : `${Math.round(m / 1440)} d ago`;
};
