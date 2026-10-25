package com.college.staffcoord.web;

import com.college.staffcoord.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    static final String COLS = "id, department_id, sender_id, sender_name, recipient_id, recipient_name, urgency, message, status, "
        + "response, created_at, delivered_at, acknowledged_at, responded_at, completed_at, escalated_at";
    static final String DEFAULT_MSG = "The HOD is calling you. Please meet the HOD now.";

    record CallReq(@NotNull UUID recipientId, @Pattern(regexp = "IMMEDIATE|SOON|ROUTINE") String urgency, @Size(max = 500) String message) {}
    record RespReq(@NotBlank @Pattern(regexp = "ACKNOWLEDGE|ON_MY_WAY|BUSY_TEACHING|REQUEST_MORE_TIME|UNABLE_TO_ATTEND") String response) {}

    private final JdbcTemplate jdbc;
    private final SseHub hub;
    private final Audit audit;

    public NotificationController(JdbcTemplate jdbc, SseHub hub, Audit audit) { this.jdbc = jdbc; this.hub = hub; this.audit = audit; }

    /** Call Staff. Department and sender come from the authenticated HOD; the recipient must be in that department. */
    @PostMapping
    @PreAuthorize("hasRole('HOD')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> call(@Valid @RequestBody CallReq r) {
        AuthUser u = AuthUser.require();
        // Row-level security limits this lookup to the HOD's own department; other departments look like "not found".
        List<UUID> ok = jdbc.queryForList("SELECT id FROM app_users WHERE id = ? AND role = 'STAFF' AND active", UUID.class, r.recipientId());
        if (ok.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found");
        String msg = (r.message() == null || r.message().isBlank()) ? DEFAULT_MSG : r.message().trim();
        Map<String, Object> row = jdbc.queryForMap(
            "INSERT INTO notifications(department_id, sender_id, recipient_id, urgency, message) VALUES (?,?,?,?,?) RETURNING " + COLS,
            u.departmentId(), u.id(), r.recipientId(), r.urgency() == null ? "IMMEDIATE" : r.urgency(), msg);
        audit.log("CALL_STAFF", u.departmentId(), r.recipientId().toString());
        hub.toUser(r.recipientId(), "notification", row);        // only the selected staff member
        return row;
    }

    /** Staff: own notifications. HOD: own department. Admin: all. Enforced by RLS, not by this query. */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "100") int limit, @RequestParam(required = false) String status) {
        int lim = Math.max(1, Math.min(limit, 500));
        return status == null
            ? jdbc.queryForList("SELECT " + COLS + " FROM notifications ORDER BY created_at DESC LIMIT ?", lim)
            : jdbc.queryForList("SELECT " + COLS + " FROM notifications WHERE status = ? ORDER BY created_at DESC LIMIT ?", status, lim);
    }

    @PostMapping("/{id}/delivered")
    @PreAuthorize("hasRole('STAFF')")
    public Map<String, Object> delivered(@PathVariable UUID id) {
        return push(one("UPDATE notifications SET status = 'DELIVERED', delivered_at = now() WHERE id = ? AND status = 'PENDING' RETURNING " + COLS, id));
    }

    @PostMapping("/{id}/respond")
    @PreAuthorize("hasRole('STAFF')")
    public Map<String, Object> respond(@PathVariable UUID id, @Valid @RequestBody RespReq r) {
        boolean ack = "ACKNOWLEDGE".equals(r.response());
        return push(one("UPDATE notifications SET status = ?, response = ?, acknowledged_at = coalesce(acknowledged_at, now()), "
            + "responded_at = CASE WHEN ? THEN responded_at ELSE now() END "
            + "WHERE id = ? AND status IN ('PENDING','DELIVERED','ESCALATED','ACKNOWLEDGED') RETURNING " + COLS,
            ack ? "ACKNOWLEDGED" : "RESPONDED", r.response(), ack, id));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasRole('HOD')")
    public Map<String, Object> complete(@PathVariable UUID id) {
        return push(one("UPDATE notifications SET status = 'COMPLETED', completed_at = now() "
            + "WHERE id = ? AND status IN ('ACKNOWLEDGED','RESPONDED','ESCALATED') RETURNING " + COLS, id));
    }

    @PostMapping("/{id}/escalate")
    @PreAuthorize("hasRole('HOD')")
    public Map<String, Object> escalate(@PathVariable UUID id) {
        Map<String, Object> row = push(one("UPDATE notifications SET status = 'ESCALATED', escalated_at = now() "
            + "WHERE id = ? AND status IN ('PENDING','DELIVERED') RETURNING " + COLS, id));
        audit.log("ESCALATE", (UUID) row.get("department_id"), id.toString());
        return row;
    }

    /** RLS decides whether the caller may touch the row; no match => 404 (never reveals other departments' rows). */
    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> l = jdbc.queryForList(sql, args);
        if (l.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found or not allowed in current state");
        return l.get(0);
    }

    private Map<String, Object> push(Map<String, Object> row) {
        hub.toUser((UUID) row.get("sender_id"), "notification-update", row);
        hub.toUser((UUID) row.get("recipient_id"), "notification-update", row);
        return row;
    }
}
