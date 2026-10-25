# Multi-Department Smart Staff Coordination & HOD Calling System

Spring Boot 3 + PostgreSQL 16 (row-level security) + React (Vite).

## Status — read this first
* Frontend: builds cleanly (`npm run build`).
* Backend + SQL: written to the spec but **not yet compiled or run** (no JDK/Maven in the authoring environment).
  Run `mvn verify` (needs Docker for Testcontainers) before trusting it; fix any compile slips first.
* **Not implemented:** OS-level Web Push (needs VAPID keys + service worker). In-app real-time delivery (SSE) and
  browser `Notification` popups while the app is open ARE implemented. Each HOD belongs to exactly one department.

## How department isolation works (defence in depth)
1. **Identity is server-side.** The JWT contains only the user id. On every request the API reloads role, department
   and active flag from the database (`app_load_user`), so deactivation/transfer takes effect immediately.
   No endpoint accepts a department id to *choose* scope; a HOD's department always comes from the account.
2. **PostgreSQL row-level security** (`V1__schema_and_rls.sql`). The API connects as `smartstaff_app`
   (not owner, not superuser, no BYPASSRLS). `RlsDataSource` stamps each pooled connection with the authenticated
   user's id/role/department; with no user the values are empty and every policy denies. A HOD running *any* SQL
   can only see/modify their own department's rows.
3. **Integrity trigger** on `notifications`: recipient must be an active STAFF member of the sender's department;
   immutable columns; staff/HOD can only move status along permitted transitions.
4. **Least privilege:** `password_hash` is not readable by the API role; column-level UPDATE grants; audit log is
   append-only. Only a few narrow `SECURITY DEFINER` functions bypass RLS (login lookup, load user, change own
   password, one-time bootstrap, aggregate summary, auto-escalation).
5. **Real-time** subscriptions are bound server-side to user/department; notifications go only to the recipient.
6. **Tests** (`IsolationIT`): API-level cross-department attempts (404/403) *and* raw-SQL attempts under a HOD's
   context, plus unauthenticated = zero rows.

Caveat: RLS context is passed via session settings. Always use parameterised SQL (the code does) so injected SQL
can never call `set_config`.

## Run it
```bash
cp .env.example .env        # fill every value; JWT_SECRET: openssl rand -base64 48
docker compose --env-file .env up -d --build        # Postgres + API on 127.0.0.1:8080
cd frontend && npm install && npm run dev            # UI on http://localhost:5173
```
Sign in with `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD`, then **remove those two variables**.
Typical setup: create departments → register a HOD and staff per department → staff set their location →
HOD clicks *Call Staff*.

Tests: `cd backend && mvn verify` (Docker required).

## Roles
| Role | Can do |
|---|---|
| SUPER_ADMIN | departments, users, HOD assignment, transfers, audit log, all reports |
| HOD | own department only: staff directory, call staff, track/complete/escalate, locations, timetable, reports |
| STAFF | own status, own notifications/responses, own timetable |
| MANAGEMENT | per-department aggregate summaries only |

## API (all under `/api`, Bearer token)
`POST auth/login` · `GET me` · `POST me/password` · `GET staff` · `GET|PUT status/me` · `GET|POST|DELETE locations` ·
`GET|POST|DELETE timetable` · `POST notifications` · `GET notifications` · `POST notifications/{id}/delivered|respond|complete|escalate` ·
`GET reports/summary` · `GET stream` (SSE) · `admin/*` (super admin).

## Production checklist
HTTPS reverse proxy (also serves `frontend/dist` and proxies `/api`, disable proxy buffering for `/api/stream`);
managed Postgres with backups; keep `DB_OWNER_*` out of the running API where possible (run migrations in CI);
rotate `JWT_SECRET`; set `CORS_ORIGIN` to your real origin; run behind a real rate limiter in addition to the
built-in login throttle (in-memory, per instance).
