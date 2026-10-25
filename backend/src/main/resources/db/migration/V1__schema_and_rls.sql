-- Restricted role used by the API. It is NOT a superuser and does NOT bypass RLS.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'smartstaff_app') THEN
    CREATE ROLE smartstaff_app LOGIN PASSWORD '${app_password}' NOSUPERUSER NOBYPASSRLS;
  ELSE
    ALTER ROLE smartstaff_app PASSWORD '${app_password}' NOSUPERUSER NOBYPASSRLS;
  END IF;
END $$;

CREATE TABLE departments (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  code varchar(16) NOT NULL UNIQUE,
  name varchar(120) NOT NULL,
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app_users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  department_id uuid REFERENCES departments(id),
  role varchar(16) NOT NULL CHECK (role IN ('SUPER_ADMIN','MANAGEMENT','HOD','STAFF')),
  staff_code varchar(32) UNIQUE,
  name varchar(120) NOT NULL,
  email varchar(200) NOT NULL,
  designation varchar(80),
  subjects text,
  photo_url varchar(300),
  password_hash varchar(100) NOT NULL,
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT dept_required CHECK ((role IN ('HOD','STAFF')) = (department_id IS NOT NULL))
);
CREATE UNIQUE INDEX ux_users_email ON app_users (lower(email));
CREATE UNIQUE INDEX ux_one_active_hod ON app_users (department_id) WHERE role = 'HOD' AND active;
CREATE INDEX ix_users_dept ON app_users (department_id);

CREATE TABLE locations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  department_id uuid NOT NULL REFERENCES departments(id),
  name varchar(80) NOT NULL,
  kind varchar(20) NOT NULL DEFAULT 'OTHER' CHECK (kind IN ('CLASSROOM','LAB','OFFICE','WORKSHOP','OTHER')),
  UNIQUE (department_id, name)
);

CREATE TABLE staff_status (
  user_id uuid PRIMARY KEY REFERENCES app_users(id) ON DELETE CASCADE,
  department_id uuid NOT NULL REFERENCES departments(id),
  location varchar(120) NOT NULL,
  activity varchar(120) NOT NULL,
  availability varchar(16) NOT NULL CHECK (availability IN ('AVAILABLE','BUSY','IN_CLASS','AWAY')),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_status_dept ON staff_status (department_id);

CREATE TABLE timetable_entries (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  department_id uuid NOT NULL REFERENCES departments(id),
  user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
  day_of_week smallint NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
  start_time time NOT NULL,
  end_time time NOT NULL,
  subject varchar(120) NOT NULL,
  location varchar(120) NOT NULL,
  CHECK (end_time > start_time)
);

CREATE TABLE notifications (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  department_id uuid NOT NULL REFERENCES departments(id),
  sender_id uuid NOT NULL REFERENCES app_users(id),
  sender_name varchar(120) NOT NULL,
  recipient_id uuid NOT NULL REFERENCES app_users(id),
  recipient_name varchar(120) NOT NULL,
  urgency varchar(10) NOT NULL DEFAULT 'IMMEDIATE' CHECK (urgency IN ('IMMEDIATE','SOON','ROUTINE')),
  message varchar(500) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','DELIVERED','ACKNOWLEDGED','RESPONDED','COMPLETED','ESCALATED')),
  response varchar(24) CHECK (response IN ('ACKNOWLEDGE','ON_MY_WAY','BUSY_TEACHING','REQUEST_MORE_TIME','UNABLE_TO_ATTEND')),
  created_at timestamptz NOT NULL DEFAULT now(),
  delivered_at timestamptz, acknowledged_at timestamptz, responded_at timestamptz,
  completed_at timestamptz, escalated_at timestamptz
);
CREATE INDEX ix_notif_dept ON notifications (department_id, created_at DESC);
CREATE INDEX ix_notif_recipient ON notifications (recipient_id, created_at DESC);

CREATE TABLE audit_log (
  id bigserial PRIMARY KEY,
  at timestamptz NOT NULL DEFAULT now(),
  actor_id uuid,
  department_id uuid,
  action varchar(60) NOT NULL,
  detail text
);

-- ---------- request context (set per connection checkout by the API; empty => deny all) ----------
CREATE FUNCTION app_uid()  RETURNS uuid LANGUAGE sql STABLE AS $$ SELECT nullif(current_setting('app.user_id', true), '')::uuid $$;
CREATE FUNCTION app_dept() RETURNS uuid LANGUAGE sql STABLE AS $$ SELECT nullif(current_setting('app.dept_id', true), '')::uuid $$;
CREATE FUNCTION app_role() RETURNS text LANGUAGE sql STABLE AS $$ SELECT nullif(current_setting('app.role', true), '') $$;
CREATE FUNCTION is_admin() RETURNS boolean LANGUAGE sql STABLE AS $$ SELECT coalesce(app_role() = 'SUPER_ADMIN', false) $$;
CREATE FUNCTION is_hod_of(d uuid) RETURNS boolean LANGUAGE sql STABLE AS
  $$ SELECT coalesce(app_role() = 'HOD' AND d IS NOT NULL AND d = app_dept(), false) $$;

-- ---------- row-level security ----------
ALTER TABLE departments       ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_users         ENABLE ROW LEVEL SECURITY;
ALTER TABLE locations         ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_status      ENABLE ROW LEVEL SECURITY;
ALTER TABLE timetable_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications     ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log         ENABLE ROW LEVEL SECURITY;

-- departments
CREATE POLICY dept_r ON departments FOR SELECT USING (app_role() IN ('SUPER_ADMIN','MANAGEMENT') OR id = app_dept());
CREATE POLICY dept_i ON departments FOR INSERT WITH CHECK (is_admin());
CREATE POLICY dept_u ON departments FOR UPDATE USING (is_admin()) WITH CHECK (is_admin());

-- users (a HOD sees only own department; staff see only themselves)
CREATE POLICY usr_r ON app_users FOR SELECT USING (is_admin() OR id = app_uid() OR is_hod_of(department_id));
CREATE POLICY usr_i ON app_users FOR INSERT WITH CHECK (is_admin());
CREATE POLICY usr_u ON app_users FOR UPDATE USING (is_admin()) WITH CHECK (is_admin());

-- locations
CREATE POLICY loc_r ON locations FOR SELECT USING (is_admin() OR department_id = app_dept());
CREATE POLICY loc_i ON locations FOR INSERT WITH CHECK (is_admin() OR is_hod_of(department_id));
CREATE POLICY loc_u ON locations FOR UPDATE USING (is_admin() OR is_hod_of(department_id)) WITH CHECK (is_admin() OR is_hod_of(department_id));
CREATE POLICY loc_d ON locations FOR DELETE USING (is_admin() OR is_hod_of(department_id));

-- staff status
CREATE POLICY st_r ON staff_status FOR SELECT USING (is_admin() OR user_id = app_uid() OR is_hod_of(department_id));
CREATE POLICY st_i ON staff_status FOR INSERT WITH CHECK (app_role() = 'STAFF' AND user_id = app_uid() AND department_id = app_dept());
CREATE POLICY st_u ON staff_status FOR UPDATE USING (app_role() = 'STAFF' AND user_id = app_uid())
  WITH CHECK (app_role() = 'STAFF' AND user_id = app_uid() AND department_id = app_dept());
CREATE POLICY st_d ON staff_status FOR DELETE USING (is_admin());

-- timetable (the user must belong to the same department as the entry; checked under RLS)
CREATE POLICY tt_r ON timetable_entries FOR SELECT USING (is_admin() OR user_id = app_uid() OR is_hod_of(department_id));
CREATE POLICY tt_i ON timetable_entries FOR INSERT WITH CHECK ((is_admin() OR is_hod_of(department_id))
  AND EXISTS (SELECT 1 FROM app_users u WHERE u.id = user_id AND u.department_id = timetable_entries.department_id));
CREATE POLICY tt_d ON timetable_entries FOR DELETE USING (is_admin() OR is_hod_of(department_id));

-- notifications
CREATE POLICY nt_r ON notifications FOR SELECT USING (is_admin() OR recipient_id = app_uid() OR is_hod_of(department_id));
CREATE POLICY nt_i ON notifications FOR INSERT WITH CHECK (is_hod_of(department_id) AND sender_id = app_uid());
CREATE POLICY nt_u ON notifications FOR UPDATE USING (recipient_id = app_uid() OR is_hod_of(department_id))
  WITH CHECK (recipient_id = app_uid() OR is_hod_of(department_id));

-- audit log: append-only
CREATE POLICY au_i ON audit_log FOR INSERT WITH CHECK (app_role() IS NOT NULL AND actor_id = app_uid());
CREATE POLICY au_r ON audit_log FOR SELECT USING (is_admin() OR is_hod_of(department_id));

-- ---------- integrity trigger: recipient must be an active STAFF of the sender's department ----------
CREATE FUNCTION notifications_guard() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE r record; s record;
BEGIN
  IF TG_OP = 'INSERT' THEN
    SELECT department_id, role, active, name INTO r FROM app_users WHERE id = NEW.recipient_id;
    SELECT department_id, name INTO s FROM app_users WHERE id = NEW.sender_id;
    IF r.department_id IS DISTINCT FROM NEW.department_id OR s.department_id IS DISTINCT FROM NEW.department_id
       OR r.role <> 'STAFF' OR NOT r.active THEN
      RAISE EXCEPTION 'recipient not in sender department' USING ERRCODE = '42501';
    END IF;
    NEW.recipient_name := r.name; NEW.sender_name := s.name;
    NEW.status := 'PENDING'; NEW.created_at := now();
  ELSE
    IF NEW.department_id <> OLD.department_id OR NEW.sender_id <> OLD.sender_id OR NEW.recipient_id <> OLD.recipient_id
       OR NEW.message <> OLD.message OR NEW.urgency <> OLD.urgency OR NEW.created_at <> OLD.created_at THEN
      RAISE EXCEPTION 'immutable notification field' USING ERRCODE = '42501';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status THEN
      IF app_role() = 'STAFF' AND NEW.status NOT IN ('DELIVERED','ACKNOWLEDGED','RESPONDED') THEN
        RAISE EXCEPTION 'staff cannot set this status' USING ERRCODE = '42501';
      END IF;
      IF app_role() = 'HOD' AND NEW.status NOT IN ('COMPLETED','ESCALATED') THEN
        RAISE EXCEPTION 'HOD cannot set this status' USING ERRCODE = '42501';
      END IF;
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_notifications_guard BEFORE INSERT OR UPDATE ON notifications
  FOR EACH ROW EXECUTE FUNCTION notifications_guard();

-- ---------- narrow SECURITY DEFINER helpers (the only places that bypass RLS) ----------
CREATE FUNCTION app_find_login(p_email text) RETURNS TABLE(id uuid, password_hash text, active boolean)
LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
  SELECT u.id, u.password_hash::text, (u.active AND coalesce(d.active, true))
  FROM app_users u LEFT JOIN departments d ON d.id = u.department_id WHERE lower(u.email) = lower(p_email) $$;

CREATE FUNCTION app_load_user(p_id uuid) RETURNS TABLE(id uuid, role text, department_id uuid, name text, email text, active boolean)
LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
  SELECT u.id, u.role::text, u.department_id, u.name::text, u.email::text, (u.active AND coalesce(d.active, true))
  FROM app_users u LEFT JOIN departments d ON d.id = u.department_id WHERE u.id = p_id $$;

CREATE FUNCTION app_set_password(p_hash text) RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
  UPDATE app_users SET password_hash = p_hash WHERE id = app_uid() $$;

CREATE FUNCTION app_bootstrap_admin(p_email text, p_name text, p_hash text) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM app_users WHERE role = 'SUPER_ADMIN') THEN RETURN false; END IF;
  INSERT INTO app_users(role, name, email, password_hash) VALUES ('SUPER_ADMIN', p_name, lower(p_email), p_hash);
  RETURN true;
END $$;

CREATE FUNCTION app_dept_summary(p_stale_minutes int) RETURNS TABLE(
  department_id uuid, code text, name text, staff_count bigint, calls_total bigint, calls_open bigint,
  calls_answered bigint, avg_response_seconds double precision, stale_staff bigint)
LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
  SELECT d.id, d.code::text, d.name::text,
    (SELECT count(*) FROM app_users u WHERE u.department_id = d.id AND u.role = 'STAFF' AND u.active),
    (SELECT count(*) FROM notifications n WHERE n.department_id = d.id),
    (SELECT count(*) FROM notifications n WHERE n.department_id = d.id AND n.status IN ('PENDING','DELIVERED','ESCALATED')),
    (SELECT count(*) FROM notifications n WHERE n.department_id = d.id AND n.status IN ('ACKNOWLEDGED','RESPONDED','COMPLETED')),
    (SELECT avg(extract(epoch FROM (coalesce(n.acknowledged_at, n.responded_at) - n.created_at)))::double precision
       FROM notifications n WHERE n.department_id = d.id AND coalesce(n.acknowledged_at, n.responded_at) IS NOT NULL),
    (SELECT count(*) FROM app_users u LEFT JOIN staff_status s ON s.user_id = u.id
       WHERE u.department_id = d.id AND u.role = 'STAFF' AND u.active
         AND (s.updated_at IS NULL OR s.updated_at < now() - make_interval(mins => p_stale_minutes)))
  FROM departments d
  WHERE app_role() IN ('SUPER_ADMIN','MANAGEMENT') OR (app_role() = 'HOD' AND d.id = app_dept())
  ORDER BY d.code $$;

CREATE FUNCTION app_escalate_overdue(p_minutes int) RETURNS TABLE(id uuid, sender_id uuid, recipient_id uuid)
LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
  UPDATE notifications n SET status = 'ESCALATED', escalated_at = now()
  WHERE n.status IN ('PENDING','DELIVERED') AND n.created_at < now() - make_interval(mins => p_minutes)
  RETURNING n.id, n.sender_id, n.recipient_id $$;

REVOKE ALL ON FUNCTION app_find_login(text), app_load_user(uuid), app_set_password(text),
  app_bootstrap_admin(text,text,text), app_dept_summary(int), app_escalate_overdue(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_find_login(text), app_load_user(uuid), app_set_password(text),
  app_bootstrap_admin(text,text,text), app_dept_summary(int), app_escalate_overdue(int) TO smartstaff_app;

-- ---------- least-privilege grants (password_hash is never readable by the API role) ----------
GRANT USAGE ON SCHEMA public TO smartstaff_app;
GRANT SELECT, INSERT, UPDATE ON departments TO smartstaff_app;
GRANT SELECT (id, department_id, role, staff_code, name, email, designation, subjects, photo_url, active, created_at)
  ON app_users TO smartstaff_app;
GRANT INSERT ON app_users TO smartstaff_app;
GRANT UPDATE (department_id, role, staff_code, name, email, designation, subjects, photo_url, active) ON app_users TO smartstaff_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON locations, staff_status TO smartstaff_app;
GRANT SELECT, INSERT, DELETE ON timetable_entries TO smartstaff_app;
GRANT SELECT, INSERT ON notifications TO smartstaff_app;
GRANT UPDATE (status, response, delivered_at, acknowledged_at, responded_at, completed_at, escalated_at)
  ON notifications TO smartstaff_app;
GRANT SELECT, INSERT ON audit_log TO smartstaff_app;
GRANT USAGE ON SEQUENCE audit_log_id_seq TO smartstaff_app;
