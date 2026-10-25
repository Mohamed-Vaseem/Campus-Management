package com.college.staffcoord;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.college.staffcoord.security.AuthUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Proves one department cannot see or touch another's data - through the API AND directly in the database. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IsolationIT {
    static final String ADMIN_PW = "Admin-Password-123", PW = "Password-12345";
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.password", () -> "test-app-pass");
        r.add("spring.flyway.url", pg::getJdbcUrl);
        r.add("spring.flyway.user", pg::getUsername);
        r.add("spring.flyway.password", pg::getPassword);
        r.add("spring.flyway.placeholders.app_password", () -> "test-app-pass");
        r.add("app.jwt-secret", () -> "x".repeat(48));
        r.add("app.bootstrap.admin-email", () -> "admin@test.edu");
        r.add("app.bootstrap.admin-password", () -> ADMIN_PW);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    String admin, hodCse, hodMech, staffCse, staffMech;
    UUID cse, mech, staffCseId, staffMechId, hodCseId, hodMechId;

    @BeforeAll
    void seed() throws Exception {
        admin = login("admin@test.edu", ADMIN_PW);
        cse = id(create(admin, "/api/admin/departments", "{\"code\":\"CSE\",\"name\":\"Computer Science\"}"));
        mech = id(create(admin, "/api/admin/departments", "{\"code\":\"MECH\",\"name\":\"Mechanical\"}"));
        hodCseId = user("HOD", cse, "hod.cse@test.edu");
        hodMechId = user("HOD", mech, "hod.mech@test.edu");
        staffCseId = user("STAFF", cse, "staff.cse@test.edu");
        staffMechId = user("STAFF", mech, "staff.mech@test.edu");
        hodCse = login("hod.cse@test.edu", PW); hodMech = login("hod.mech@test.edu", PW);
        staffCse = login("staff.cse@test.edu", PW); staffMech = login("staff.mech@test.edu", PW);
    }

    @Test
    void hodSeesOnlyOwnDepartmentStaff() throws Exception {
        String body = send(get("/api/staff"), hodCse, null, 200);
        assertTrue(body.contains(staffCseId.toString()));
        assertFalse(body.contains(staffMechId.toString()));
        // asking for another department explicitly still returns nothing
        assertEquals("[]", send(get("/api/staff?departmentId=" + mech), hodCse, null, 200));
    }

    @Test
    void hodCannotCallStaffOfAnotherDepartment() throws Exception {
        send(post("/api/notifications"), hodMech, "{\"recipientId\":\"" + staffCseId + "\"}", 404);
    }

    @Test
    void notificationIsVisibleOnlyToSenderDepartmentAndRecipient() throws Exception {
        JsonNode n = json.readTree(send(post("/api/notifications"), hodCse, "{\"recipientId\":\"" + staffCseId + "\"}", 201));
        String nid = n.get("id").asText();
        assertTrue(send(get("/api/notifications"), hodCse, null, 200).contains(nid));
        assertTrue(send(get("/api/notifications"), staffCse, null, 200).contains(nid));
        assertEquals("[]", send(get("/api/notifications"), hodMech, null, 200));
        assertEquals("[]", send(get("/api/notifications"), staffMech, null, 200));
        send(post("/api/notifications/" + nid + "/respond"), staffMech, "{\"response\":\"ACKNOWLEDGE\"}", 404);
        send(post("/api/notifications/" + nid + "/complete"), hodMech, null, 404);
        send(post("/api/notifications/" + nid + "/respond"), staffCse, "{\"response\":\"ON_MY_WAY\"}", 200);
    }

    @Test
    void roleChecksAreEnforced() throws Exception {
        send(post("/api/notifications"), staffCse, "{\"recipientId\":\"" + staffCseId + "\"}", 403);
        send(get("/api/admin/users"), hodCse, null, 403);
        send(get("/api/staff"), null, null, 401);
    }

    @Test
    void databaseEnforcesIsolationEvenForRawSql() {
        // no authenticated user => deny by default
        assertEquals(0, count("SELECT count(*) FROM app_users", null));
        // HOD of MECH sees only MECH rows when running arbitrary SQL
        AuthUser mechHod = new AuthUser(hodMechId, "HOD", mech, "m", "hod.mech@test.edu");
        assertEquals(2, count("SELECT count(*) FROM app_users", mechHod));
        assertEquals(0, count("SELECT count(*) FROM app_users WHERE department_id = '" + cse + "'", mechHod));
        // ...and cannot insert a notification for the CSE department, nor to a CSE recipient
        assertThrows(DataAccessException.class, () -> run(mechHod, () -> jdbc.update(
            "INSERT INTO notifications(department_id, sender_id, recipient_id, message) VALUES (?,?,?, 'x')", cse, hodMechId, staffCseId)));
        assertThrows(DataAccessException.class, () -> run(mechHod, () -> jdbc.update(
            "INSERT INTO notifications(department_id, sender_id, recipient_id, message) VALUES (?,?,?, 'x')", mech, hodMechId, staffCseId)));
        // password hashes are not readable by the application role at all
        assertThrows(DataAccessException.class, () -> run(mechHod, () -> jdbc.queryForList("SELECT password_hash FROM app_users")));
    }

    // ---------- helpers ----------
    int count(String sql, AuthUser as) { return run(as, () -> jdbc.queryForObject(sql, Integer.class)); }

    <T> T run(AuthUser as, java.util.function.Supplier<T> s) {
        SecurityContext prev = SecurityContextHolder.getContext();
        try {
            SecurityContext ctx = new SecurityContextImpl();
            if (as != null) ctx.setAuthentication(new UsernamePasswordAuthenticationToken(as, null, java.util.List.of()));
            SecurityContextHolder.setContext(ctx);
            return s.get();
        } finally { SecurityContextHolder.setContext(prev); }
    }

    UUID user(String role, UUID dept, String email) throws Exception {
        return id(create(admin, "/api/admin/users", String.format(
            "{\"role\":\"%s\",\"departmentId\":\"%s\",\"name\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}", role, dept, email, email, PW)));
    }
    UUID id(String body) throws Exception { return UUID.fromString(json.readTree(body).get("id").asText()); }
    String login(String email, String pw) throws Exception {
        return json.readTree(send(post("/api/auth/login"), null, "{\"email\":\"" + email + "\",\"password\":\"" + pw + "\"}", 200)).get("token").asText();
    }
    String create(String token, String path, String body) throws Exception { return send(post(path), token, body, 201); }
    String send(MockHttpServletRequestBuilder b, String token, String body, int expect) throws Exception {
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(b).andExpect(status().is(expect)).andReturn().getResponse().getContentAsString();
    }
}
