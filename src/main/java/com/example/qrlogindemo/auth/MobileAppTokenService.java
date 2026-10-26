package com.example.qrlogindemo.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class MobileAppTokenService {
    private static final Duration LIFETIME = Duration.ofHours(12);
    private final SecureRandom random = new SecureRandom();
    private final Map<String, MobileToken> tokens = new ConcurrentHashMap<>();

    public String issue(String username) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(token, new MobileToken(username, Instant.now().plus(LIFETIME)));
        return token;
    }

    public String usernameFor(String token) {
        MobileToken stored = tokens.get(token);
        if (stored == null) return null;
        if (!stored.expiresAt().isAfter(Instant.now())) {
            tokens.remove(token, stored);
            return null;
        }
        return stored.username();
    }

    public void revoke(String token) {
        tokens.remove(token);
    }

    private record MobileToken(String username, Instant expiresAt) {}
}
