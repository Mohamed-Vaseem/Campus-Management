package com.college.staffcoord.web;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final JdbcTemplate jdbc;
    private final int stale;
    public ReportController(JdbcTemplate jdbc, @Value("${app.stale-minutes}") int stale) { this.jdbc = jdbc; this.stale = stale; }

    /** HOD => own department only; SUPER_ADMIN / MANAGEMENT => per-department aggregates. The function enforces scope. */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('HOD','SUPER_ADMIN','MANAGEMENT')")
    public List<Map<String, Object>> summary() {
        return jdbc.queryForList("SELECT * FROM app_dept_summary(?)", stale);
    }
}
