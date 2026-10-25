package com.college.staffcoord.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final JdbcTemplate jdbc;

    public JwtFilter(JwtService jwt, JdbcTemplate jdbc) { this.jwt = jwt; this.jdbc = jdbc; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            try {
                UUID id = jwt.parse(h.substring(7));
                // Runs with an empty RLS context; the SECURITY DEFINER function returns only this one user.
                List<AuthUser> found = jdbc.query(
                    "SELECT id, role, department_id, name, email FROM app_load_user(?) WHERE active",
                    (rs, i) -> new AuthUser(rs.getObject("id", UUID.class), rs.getString("role"),
                        rs.getObject("department_id", UUID.class), rs.getString("name"), rs.getString("email")), id);
                if (!found.isEmpty()) {
                    AuthUser u = found.get(0);
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        u, null, List.of(new SimpleGrantedAuthority("ROLE_" + u.role()))));
                }
            } catch (JwtException | IllegalArgumentException ignored) { /* unauthenticated */ }
        }
        chain.doFilter(req, res);
    }
}
