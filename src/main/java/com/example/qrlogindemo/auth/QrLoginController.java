package com.example.qrlogindemo.auth;

import com.example.qrlogindemo.auth.QrLoginSessionService.QrLoginSession;
import com.example.qrlogindemo.auth.QrLoginSessionService.State;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class QrLoginController {
    private final QrLoginSessionService sessions;
    private final MobileAppTokenService mobileTokens;
    private final UserDetailsService users;
    private final SecurityContextRepository contexts;
    private final String publicBaseUrl;

    public QrLoginController(
            QrLoginSessionService sessions,
            MobileAppTokenService mobileTokens,
            UserDetailsService users,
            SecurityContextRepository contexts,
            @Value("${app.public-base-url}") String publicBaseUrl) {
        this.sessions = sessions;
        this.mobileTokens = mobileTokens;
        this.users = users;
        this.contexts = contexts;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    @GetMapping("/csrf")
    Map<String, String> browserCsrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @GetMapping("/mobile/csrf")
    Map<String, String> mobileCsrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping("/mobile/login")
    Map<String, String> mobileLogin(Authentication authentication) {
        return Map.of("token", mobileTokens.issue(authentication.getName()), "username", authentication.getName());
    }

    @GetMapping("/mobile/me")
    Map<String, String> mobileMe(Authentication authentication) {
        return Map.of("username", authentication.getName());
    }

    @PostMapping("/mobile/logout")
    void mobileLogout(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            mobileTokens.revoke(authorization.substring(7));
        }
    }

    @PostMapping("/qr-sessions")
    QrSessionResponse create(HttpServletRequest request) {
        QrLoginSession session = sessions.create(request.getSession(true).getId());
        String mobileUrl = publicBaseUrl + "/mobile.html?ticket=" + session.ticket();
        return new QrSessionResponse(session.ticket(), session.expiresAt().toString(), mobileUrl);
    }

    @GetMapping("/qr-sessions/{ticket}/qr")
    void qr(@PathVariable String ticket, HttpServletRequest request, HttpServletResponse response)
            throws WriterException, IOException {
        sessions.findForBrowser(ticket, sessionId(request));
        String mobileUrl = publicBaseUrl + "/mobile.html?ticket=" + ticket;
        var matrix = new QRCodeWriter().encode(mobileUrl, BarcodeFormat.QR_CODE, 280, 280);
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", image);
        response.setContentType(MediaType.IMAGE_PNG_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        response.getOutputStream().write(image.toByteArray());
    }

    @GetMapping("/qr-sessions/{ticket}")
    QrStatus status(
            @PathVariable String ticket,
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication currentAuthentication) {
        QrLoginSession session = sessions.findForBrowser(ticket, sessionId(request));
        if (session.state() == State.APPROVED
                && (currentAuthentication == null || currentAuthentication instanceof AnonymousAuthenticationToken)) {
            authenticateBrowser(session.username(), request, response);
        }
        return new QrStatus(session.state().name(), session.expiresAt().toString());
    }

    @PostMapping("/mobile/qr-sessions/{ticket}/scan")
    Map<String, String> scan(@PathVariable String ticket, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        return Map.of("state", sessions.scan(ticket).state().name());
    }

    @PostMapping("/mobile/qr-sessions/{ticket}/approve")
    Map<String, String> approve(@PathVariable String ticket, Authentication authentication) {
        return Map.of(
                "state",
                sessions.approve(ticket, authentication.getName()).state().name());
    }

    @GetMapping("/me")
    Map<String, String> me(Authentication authentication) {
        return Map.of("username", authentication.getName());
    }

    @PostMapping("/logout")
    void logout(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        contexts.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
    }

    private void authenticateBrowser(String username, HttpServletRequest request, HttpServletResponse response) {
        UserDetails user = users.loadUserByUsername(username);
        Authentication authentication =
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }

    private String sessionId(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        }
        return session.getId();
    }

    record QrSessionResponse(String ticket, String expiresAt, String mobileUrl) {}

    record QrStatus(String state, String expiresAt) {}
}
