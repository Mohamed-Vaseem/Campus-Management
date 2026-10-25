package com.college.staffcoord.web;

import com.college.staffcoord.security.AuthUser;
import com.college.staffcoord.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class AuthController {
    record LoginReq(@NotBlank @Size(max = 200) String email, @NotBlank @Size(max = 200) String password) {}
    record PwReq(@NotBlank String current, @NotBlank @Size(min = 10, max = 100) String next) {}

    private static final int MAX_FAILS = 5;
    private static final long WINDOW_MS = 10 * 60 * 1000L;
    private final Map<String, Deque<Long>> fails = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final PasswordEncoder enc;
    private final JwtService jwt;
    private final String dummyHash;

    public AuthController(JdbcTemplate jdbc, PasswordEncoder enc, JwtService jwt) {
        this.jdbc = jdbc; this.enc = enc; this.jwt = jwt;
        this.dummyHash = enc.encode("not-a-real-password");   // equalises timing for unknown emails
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@Valid @RequestBody LoginReq r, HttpServletRequest req) {
        String key = r.email().toLowerCase() + "|" + req.getRemoteAddr();
        if (recentFails(key) >= MAX_FAILS) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts");
        List<Object[]> rows = jdbc.query("SELECT id, password_hash, active FROM app_find_login(?)",
            (rs, i) -> new Object[]{rs.getObject("id", UUID.class), rs.getString("password_hash"), rs.getBoolean("active")}, r.email());
        boolean found = !rows.isEmpty();
        boolean pwOk = enc.matches(r.password(), found ? (String) rows.get(0)[1] : dummyHash);
        if (!(found && pwOk && (Boolean) rows.get(0)[2])) {
            fails.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(System.currentTimeMillis());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        fails.remove(key);
        return Map.of("token", jwt.issue((UUID) rows.get(0)[0]));
    }

    private int recentFails(String key) {
        Deque<Long> d = fails.get(key);
        if (d == null) return 0;
        synchronized (d) {
            long cutoff = System.currentTimeMillis() - WINDOW_MS;
            while (!d.isEmpty() && d.peekFirst() < cutoff) d.pollFirst();
            return d.size();
        }
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        AuthUser u = AuthUser.require();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id()); m.put("role", u.role()); m.put("name", u.name()); m.put("email", u.email());
        m.put("department_id", u.departmentId());
        if (u.departmentId() != null) {
            List<Map<String, Object>> d = jdbc.queryForList("SELECT code, name FROM departments WHERE id = ?", u.departmentId());
            if (!d.isEmpty()) { m.put("department_code", d.get(0).get("code")); m.put("department_name", d.get(0).get("name")); }
        }
        return m;
    }

    @PostMapping("/me/password")
    public void changePassword(@Valid @RequestBody PwReq r) {
        AuthUser u = AuthUser.require();
        List<String> h = jdbc.query("SELECT password_hash FROM app_find_login(?)", (rs, i) -> rs.getString(1), u.email());
        if (h.isEmpty() || !enc.matches(r.current(), h.get(0))) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong password");
        jdbc.queryForList("SELECT app_set_password(?)", enc.encode(r.next()));
    }
}
