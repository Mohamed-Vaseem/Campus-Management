package com.college.staffcoord.security;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Identity loaded from the database on every request. Department comes from here, never from the client. */
public record AuthUser(UUID id, String role, UUID departmentId, String name, String email) {
    public static AuthUser current() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return (a != null && a.getPrincipal() instanceof AuthUser u) ? u : null;
    }
    public static AuthUser require() {
        AuthUser u = current();
        if (u == null) throw new AccessDeniedException("Not authenticated");
        return u;
    }
}
