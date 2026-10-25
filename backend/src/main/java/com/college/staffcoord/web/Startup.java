package com.college.staffcoord.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the first super admin once (only if none exists). */
@Component
class BootstrapAdmin implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);
    private final JdbcTemplate jdbc;
    private final PasswordEncoder enc;
    private final String email, password;

    BootstrapAdmin(JdbcTemplate jdbc, PasswordEncoder enc, @Value("${app.bootstrap.admin-email:}") String email,
                   @Value("${app.bootstrap.admin-password:}") String password) {
        this.jdbc = jdbc; this.enc = enc; this.email = email; this.password = password;
    }

    @Override public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) return;
        if (password.length() < 12) { log.warn("BOOTSTRAP_ADMIN_PASSWORD shorter than 12 chars; super admin not created"); return; }
        Boolean created = jdbc.queryForObject("SELECT app_bootstrap_admin(?, ?, ?)", Boolean.class, email, "College Administrator", enc.encode(password));
        if (Boolean.TRUE.equals(created)) log.info("Created initial super admin {}. Remove BOOTSTRAP_ADMIN_* from the environment now.", email);
    }
}

/** Unanswered calls escalate automatically; the sending HOD is told in real time. */
@Component
class EscalationJob {
    private final JdbcTemplate jdbc;
    private final SseHub hub;
    private final int minutes;

    EscalationJob(JdbcTemplate jdbc, SseHub hub, @Value("${app.escalate-minutes}") int minutes) { this.jdbc = jdbc; this.hub = hub; this.minutes = minutes; }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    void run() {
        jdbc.query("SELECT id, sender_id FROM app_escalate_overdue(?)", rs -> {
            hub.toUser(rs.getObject("sender_id", java.util.UUID.class), "escalated", java.util.Map.of("id", rs.getObject("id", java.util.UUID.class)));
        }, minutes);
    }
}
