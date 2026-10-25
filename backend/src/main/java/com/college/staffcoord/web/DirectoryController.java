package com.college.staffcoord.web;

import com.college.staffcoord.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Staff directory, self-reported location/availability, work locations and timetable. */
@RestController
@RequestMapping("/api")
public class DirectoryController {
    record StatusReq(@NotBlank @Size(max = 120) String location, @NotBlank @Size(max = 120) String activity,
                     @NotBlank @Pattern(regexp = "AVAILABLE|BUSY|IN_CLASS|AWAY") String availability) {}
    record LocReq(@NotBlank @Size(max = 80) String name, @Pattern(regexp = "CLASSROOM|LAB|OFFICE|WORKSHOP|OTHER") String kind, UUID departmentId) {}
    record TtReq(@NotNull UUID userId, @Min(1) @Max(7) int dayOfWeek,
                 @NotBlank @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]") String startTime,
                 @NotBlank @Pattern(regexp = "([01][0-9]|2[0-3]):[0-5][0-9]") String endTime,
                 @NotBlank @Size(max = 120) String subject, @NotBlank @Size(max = 120) String location) {}

    private final JdbcTemplate jdbc;
    private final SseHub hub;
    private final Audit audit;
    private final int staleMinutes;

    public DirectoryController(JdbcTemplate jdbc, SseHub hub, Audit audit, @Value("${app.stale-minutes}") int staleMinutes) {
        this.jdbc = jdbc; this.hub = hub; this.audit = audit; this.staleMinutes = staleMinutes;
    }

    /** Row-level security scopes this to the caller's department (a HOD passing another departmentId gets nothing). */
    @GetMapping("/staff")
    @PreAuthorize("hasAnyRole('HOD','SUPER_ADMIN')")
    public List<Map<String, Object>> staff(@RequestParam(required = false) UUID departmentId) {
        String sql = "SELECT u.id, u.department_id, u.name, u.staff_code, u.designation, u.subjects, u.photo_url, "
            + "s.location, s.activity, s.availability, s.updated_at, "
            + "(s.updated_at IS NULL OR s.updated_at < now() - make_interval(mins => ?)) AS stale "
            + "FROM app_users u LEFT JOIN staff_status s ON s.user_id = u.id WHERE u.role = 'STAFF' AND u.active"
            + (departmentId != null ? " AND u.department_id = ?" : "") + " ORDER BY u.name";
        return departmentId != null ? jdbc.queryForList(sql, staleMinutes, departmentId) : jdbc.queryForList(sql, staleMinutes);
    }

    @GetMapping("/status/me")
    @PreAuthorize("hasRole('STAFF')")
    public Map<String, Object> myStatus() {
        List<Map<String, Object>> l = jdbc.queryForList(
            "SELECT location, activity, availability, updated_at FROM staff_status WHERE user_id = ?", AuthUser.require().id());
        return l.isEmpty() ? Map.of() : l.get(0);
    }

    @PutMapping("/status/me")
    @PreAuthorize("hasRole('STAFF')")
    public Map<String, Object> setStatus(@Valid @RequestBody StatusReq r) {
        AuthUser u = AuthUser.require();
        Map<String, Object> row = jdbc.queryForMap(
            "INSERT INTO staff_status(user_id, department_id, location, activity, availability, updated_at) VALUES (?,?,?,?,?, now()) "
            + "ON CONFLICT (user_id) DO UPDATE SET department_id = EXCLUDED.department_id, location = EXCLUDED.location, "
            + "activity = EXCLUDED.activity, availability = EXCLUDED.availability, updated_at = now() "
            + "RETURNING user_id, location, activity, availability, updated_at",
            u.id(), u.departmentId(), r.location(), r.activity(), r.availability());
        Map<String, Object> evt = new HashMap<>(row);
        evt.put("name", u.name());
        hub.toDeptHods(u.departmentId(), "status", evt);
        return row;
    }

    @GetMapping("/locations")
    public List<Map<String, Object>> locations(@RequestParam(required = false) UUID departmentId) {
        String sql = "SELECT id, department_id, name, kind FROM locations" + (departmentId != null ? " WHERE department_id = ?" : "") + " ORDER BY name";
        return departmentId != null ? jdbc.queryForList(sql, departmentId) : jdbc.queryForList(sql);
    }

    @PostMapping("/locations")
    @PreAuthorize("hasAnyRole('HOD','SUPER_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addLocation(@Valid @RequestBody LocReq r) {
        AuthUser u = AuthUser.require();
        UUID dept = "HOD".equals(u.role()) ? u.departmentId() : r.departmentId();   // HOD: client value is ignored
        if (dept == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "departmentId required");
        UUID id = jdbc.queryForObject("INSERT INTO locations(department_id, name, kind) VALUES (?,?,?) RETURNING id", UUID.class,
            dept, r.name(), r.kind() == null ? "OTHER" : r.kind());
        return Map.of("id", id);
    }

    @DeleteMapping("/locations/{id}")
    @PreAuthorize("hasAnyRole('HOD','SUPER_ADMIN')")
    public void deleteLocation(@PathVariable UUID id) {
        if (jdbc.update("DELETE FROM locations WHERE id = ?", id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @GetMapping("/timetable")
    public List<Map<String, Object>> timetable(@RequestParam(required = false) UUID userId) {
        String sql = "SELECT id, user_id, department_id, day_of_week, to_char(start_time,'HH24:MI') AS start_time, "
            + "to_char(end_time,'HH24:MI') AS end_time, subject, location FROM timetable_entries"
            + (userId != null ? " WHERE user_id = ?" : "") + " ORDER BY day_of_week, start_time";
        return userId != null ? jdbc.queryForList(sql, userId) : jdbc.queryForList(sql);
    }

    @PostMapping("/timetable")
    @PreAuthorize("hasRole('HOD')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addTimetable(@Valid @RequestBody TtReq r) {
        AuthUser u = AuthUser.require();
        UUID id = jdbc.queryForObject(
            "INSERT INTO timetable_entries(department_id, user_id, day_of_week, start_time, end_time, subject, location) "
            + "VALUES (?,?,?,?::time,?::time,?,?) RETURNING id", UUID.class,
            u.departmentId(), r.userId(), r.dayOfWeek(), r.startTime(), r.endTime(), r.subject(), r.location());
        return Map.of("id", id);
    }

    @DeleteMapping("/timetable/{id}")
    @PreAuthorize("hasAnyRole('HOD','SUPER_ADMIN')")
    public void deleteTimetable(@PathVariable UUID id) {
        if (jdbc.update("DELETE FROM timetable_entries WHERE id = ?", id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
