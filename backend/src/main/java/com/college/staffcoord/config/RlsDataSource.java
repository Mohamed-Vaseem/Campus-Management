package com.college.staffcoord.config;

import com.college.staffcoord.security.AuthUser;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Every connection handed to application code is stamped with the authenticated user's id, role and
 * department (taken from the server-side SecurityContext, never from the request). PostgreSQL row-level
 * security policies read these settings. No authenticated user => empty values => every policy denies.
 */
public class RlsDataSource extends DelegatingDataSource implements AutoCloseable {
    private static final String SET =
        "SELECT set_config('app.user_id', ?, false), set_config('app.role', ?, false), set_config('app.dept_id', ?, false)";
    private final HikariDataSource pool;

    public RlsDataSource(HikariDataSource pool) { super(pool); this.pool = pool; }

    @Override public Connection getConnection() throws SQLException { return wrap(super.getConnection()); }
    @Override public Connection getConnection(String u, String p) throws SQLException { return wrap(super.getConnection(u, p)); }

    private Connection wrap(Connection c) throws SQLException {
        try {
            AuthUser u = AuthUser.current();
            apply(c, u == null ? "" : u.id().toString(), u == null ? "" : u.role(),
                  u == null || u.departmentId() == null ? "" : u.departmentId().toString());
        } catch (SQLException | RuntimeException e) { c.close(); throw e; }
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, m, args) -> {
            if ("close".equals(m.getName())) {
                try { apply(c, "", "", ""); } catch (Exception ignored) { /* next checkout overwrites anyway */ }
            }
            try { return m.invoke(c, args); } catch (InvocationTargetException e) { throw e.getCause(); }
        });
    }

    private static void apply(Connection c, String uid, String role, String dept) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SET)) {
            ps.setString(1, uid); ps.setString(2, role); ps.setString(3, dept);
            ps.execute();
        }
    }

    @Override public void close() { pool.close(); }
}
