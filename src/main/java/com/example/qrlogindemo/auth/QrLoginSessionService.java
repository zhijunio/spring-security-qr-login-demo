package com.example.qrlogindemo.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class QrLoginSessionService {
    private static final Duration LIFETIME = Duration.ofMinutes(2);
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Map<String, QrLoginSession> sessions = new ConcurrentHashMap<>();

    public QrLoginSessionService() {
        this(Clock.systemUTC());
    }

    QrLoginSessionService(Clock clock) {
        this.clock = clock;
    }

    public QrLoginSession create(String browserSessionId) {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        QrLoginSession session = new QrLoginSession(ticket, browserSessionId, now.plus(LIFETIME), State.PENDING, null);
        sessions.put(ticket, session);
        return session;
    }

    public QrLoginSession findForBrowser(String ticket, String browserSessionId) {
        QrLoginSession session = find(ticket);
        if (!session.browserSessionId().equals(browserSessionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!session.expiresAt().isAfter(clock.instant())) {
            sessions.remove(ticket, session);
            return new QrLoginSession(ticket, browserSessionId, session.expiresAt(), State.EXPIRED, null);
        }
        return session;
    }

    public QrLoginSession scan(String ticket) {
        return sessions.compute(ticket, (key, session) -> {
            requireLive(session);
            if (session.state() == State.PENDING) {
                return session.withState(State.SCANNED, null);
            }
            return session;
        });
    }

    public QrLoginSession approve(String ticket, String username) {
        return sessions.compute(ticket, (key, session) -> {
            requireLive(session);
            if (session.state() != State.SCANNED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "QR session must be scanned before approval");
            }
            return session.withState(State.APPROVED, username);
        });
    }

    private QrLoginSession find(String ticket) {
        QrLoginSession session = sessions.get(ticket);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return session;
    }

    private void requireLive(QrLoginSession session) {
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!session.expiresAt().isAfter(clock.instant())) {
            sessions.remove(session.ticket(), session);
            throw new ResponseStatusException(HttpStatus.GONE, "QR session expired");
        }
    }

    public enum State {
        PENDING,
        SCANNED,
        APPROVED,
        EXPIRED
    }

    public record QrLoginSession(
            String ticket, String browserSessionId, Instant expiresAt, State state, String username) {
        QrLoginSession withState(State next, String approvedUser) {
            return new QrLoginSession(ticket, browserSessionId, expiresAt, next, approvedUser);
        }
    }
}
