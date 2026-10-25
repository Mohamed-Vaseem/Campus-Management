package com.college.staffcoord.web;

import com.college.staffcoord.security.AuthUser;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Audit {
    private final JdbcTemplate jdbc;
    public Audit(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void log(String action, UUID departmentId, String detail) {
        AuthUser u = AuthUser.require();
        jdbc.update("INSERT INTO audit_log(actor_id, department_id, action, detail) VALUES (?,?,?,?)",
            u.id(), departmentId, action, detail);
    }
}
