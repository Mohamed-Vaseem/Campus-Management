package com.college.staffcoord.web;

import com.college.staffcoord.security.AuthUser;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Real-time push. A subscription is bound to the user id / role / department resolved server-side at
 * subscribe time, so streams enforce the same recipient and department rules as the REST API.
 */
@Component
public class SseHub {
    private record Sub(UUID userId, String role, UUID deptId, SseEmitter em) {}
    private final Set<Sub> subs = ConcurrentHashMap.newKeySet();

    public SseEmitter subscribe(AuthUser u) {
        SseEmitter em = new SseEmitter(30 * 60 * 1000L);   // client reconnects with a valid token
        Sub s = new Sub(u.id(), u.role(), u.departmentId(), em);
        subs.add(s);
        em.onCompletion(() -> subs.remove(s));
        em.onTimeout(() -> subs.remove(s));
        em.onError(e -> subs.remove(s));
        return em;
    }

    public void toUser(UUID userId, String event, Object data) {
        subs.stream().filter(s -> s.userId.equals(userId)).forEach(s -> send(s, event, data));
    }

    public void toDeptHods(UUID deptId, String event, Object data) {
        subs.stream().filter(s -> "HOD".equals(s.role) && deptId != null && deptId.equals(s.deptId))
            .forEach(s -> send(s, event, data));
    }

    /** Called when a user is deactivated, transferred or has their role changed. */
    public void disconnect(UUID userId) {
        subs.stream().filter(s -> s.userId.equals(userId)).forEach(s -> { subs.remove(s); try { s.em.complete(); } catch (Exception ignored) {} });
    }

    private void send(Sub s, String event, Object data) {
        try { s.em.send(SseEmitter.event().name(event).data(data)); } catch (Exception e) { subs.remove(s); }
    }

    @Scheduled(fixedRate = 25000)
    void heartbeat() {
        subs.forEach(s -> { try { s.em.send(SseEmitter.event().comment("ping")); } catch (Exception e) { subs.remove(s); } });
    }
}
