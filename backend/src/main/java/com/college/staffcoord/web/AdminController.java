package com.college.staffcoord.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** College-wide administration. Class-level role check, and the database independently enforces is_admin(). */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminController {
    record DeptReq(@NotBlank @Size(max = 16) @Pattern(regexp = "[A-Za-z0-9_-]+") String code, @NotBlank @Size(max = 120) String name) {}
    record DeptPatch(@Size(max = 120) String name, Boolean active) {}
    record UserReq(@NotBlank @Pattern(regexp = "SUPER_ADMIN|MANAGEMENT|HOD|STAFF") String role, UUID departmentId,
                   @Size(max = 32) String staffCode, @NotBlank @Size(max = 120) String name,
                   @NotBlank @Email @Size(max = 200) String email, @Size(max = 80) String designation,
                   @Size(max = 500) String subjects, @NotBlank @Size(min = 10, max = 100) String password) {}
    record UserPatch(@Size(max = 120) String name, @Size(max = 80) String designation, @Size(max = 500) String subjects, Boolean active) {}
    record UserRef(@NotNull UUID userId) {}
    record TransferReq(@NotNull UUID departmentId) {}

    private final JdbcTemplate jdbc;
    private final Audit audit;
    private final PasswordEncoder enc;
    private final SseHub hub;

    public AdminController(JdbcTemplate jdbc, Audit audit, PasswordEncoder enc, SseHub hub) {
        this.jdbc = jdbc; this.audit = audit; this.enc = enc; this.hub = hub;
    }

    @GetMapping("/departments")
    public List<Map<String, Object>> departments() {
        return jdbc.queryForList("SELECT id, code, name, active, created_at FROM departments ORDER BY code");
    }

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createDept(@Valid @RequestBody DeptReq r) {
        UUID id = jdbc.queryForObject("INSERT INTO departments(code, name) VALUES (?,?) RETURNING id", UUID.class,
            r.code().toUpperCase(), r.name());
        audit.log("DEPT_CREATE", id, r.code().toUpperCase());
        return Map.of("id", id);
    }

    @PatchMapping("/departments/{id}")
    public void patchDept(@PathVariable UUID id, @Valid @RequestBody DeptPatch p) {
        int n = jdbc.update("UPDATE departments SET name = coalesce(?, name), active = coalesce(?, active) WHERE id = ?",
            p.name(), p.active(), id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (Boolean.FALSE.equals(p.active()))
            jdbc.queryForList("SELECT id FROM app_users WHERE department_id = ?", UUID.class, id).forEach(hub::disconnect);
        audit.log("DEPT_UPDATE", id, "active=" + p.active());
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users(@RequestParam(required = false) UUID departmentId) {
        String sql = "SELECT id, department_id, role, staff_code, name, email, designation, subjects, active FROM app_users"
            + (departmentId != null ? " WHERE department_id = ?" : "") + " ORDER BY name";
        return departmentId != null ? jdbc.queryForList(sql, departmentId) : jdbc.queryForList(sql);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createUser(@Valid @RequestBody UserReq r) {
        UUID id = jdbc.queryForObject(
            "INSERT INTO app_users(department_id, role, staff_code, name, email, designation, subjects, password_hash) "
            + "VALUES (?,?,?,?,?,?,?,?) RETURNING id", UUID.class,
            r.departmentId(), r.role(), r.staffCode(), r.name(), r.email().toLowerCase(), r.designation(), r.subjects(),
            enc.encode(r.password()));
        audit.log("USER_CREATE", r.departmentId(), r.role() + ":" + id);
        return Map.of("id", id);
    }

    @PatchMapping("/users/{id}")
    public void patchUser(@PathVariable UUID id, @Valid @RequestBody UserPatch p) {
        int n = jdbc.update("UPDATE app_users SET name = coalesce(?, name), designation = coalesce(?, designation), "
            + "subjects = coalesce(?, subjects), active = coalesce(?, active) WHERE id = ?",
            p.name(), p.designation(), p.subjects(), p.active(), id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (Boolean.FALSE.equals(p.active())) hub.disconnect(id);
        audit.log("USER_UPDATE", null, id + " active=" + p.active());
    }

    @PostMapping("/departments/{id}/hod")
    @Transactional
    public void assignHod(@PathVariable UUID id, @Valid @RequestBody UserRef ref) {
        jdbc.queryForList("SELECT id FROM app_users WHERE department_id = ? AND role = 'HOD'", UUID.class, id).forEach(hub::disconnect);
        jdbc.update("UPDATE app_users SET role = 'STAFF' WHERE department_id = ? AND role = 'HOD'", id);
        int n = jdbc.update("UPDATE app_users SET role = 'HOD' WHERE id = ? AND department_id = ? AND active", ref.userId(), id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User is not an active member of that department");
        hub.disconnect(ref.userId());
        audit.log("HOD_ASSIGN", id, ref.userId().toString());
    }

    /** Controlled transfer: HOD must be replaced first; live status and timetable are reset; notification history is kept. */
    @PostMapping("/users/{id}/transfer")
    @Transactional
    public void transfer(@PathVariable UUID id, @Valid @RequestBody TransferReq r) {
        Map<String, Object> u;
        try { u = jdbc.queryForMap("SELECT role, department_id FROM app_users WHERE id = ?", id); }
        catch (EmptyResultDataAccessException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
        if (!"STAFF".equals(u.get("role"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only staff can be transferred");
        Integer ok = jdbc.queryForObject("SELECT count(*) FROM departments WHERE id = ? AND active", Integer.class, r.departmentId());
        if (ok == null || ok == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Target department not found");
        jdbc.update("DELETE FROM staff_status WHERE user_id = ?", id);
        jdbc.update("DELETE FROM timetable_entries WHERE user_id = ?", id);
        jdbc.update("UPDATE app_users SET department_id = ? WHERE id = ?", r.departmentId(), id);
        audit.log("STAFF_TRANSFER", r.departmentId(), id + " from " + u.get("department_id"));
        hub.disconnect(id);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam(defaultValue = "100") int limit) {
        return jdbc.queryForList("SELECT id, at, actor_id, department_id, action, detail FROM audit_log ORDER BY id DESC LIMIT ?",
            Math.max(1, Math.min(limit, 500)));
    }
}
