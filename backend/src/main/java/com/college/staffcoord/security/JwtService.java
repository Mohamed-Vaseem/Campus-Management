package com.college.staffcoord.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The token carries ONLY the user id. Role/department are re-read from the DB on each request. */
@Component
public class JwtService {
    private final SecretKey key;
    private final long minutes;

    public JwtService(@Value("${app.jwt-secret}") String secret, @Value("${app.jwt-minutes}") long minutes) {
        byte[] b = secret.getBytes(StandardCharsets.UTF_8);
        if (b.length < 32) throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        this.key = Keys.hmacShaKeyFor(b);
        this.minutes = minutes;
    }

    public String issue(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder().subject(userId.toString()).issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(minutes, ChronoUnit.MINUTES))).signWith(key).compact();
    }

    public UUID parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return UUID.fromString(c.getSubject());
    }
}
