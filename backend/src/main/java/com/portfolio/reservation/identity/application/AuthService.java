package com.portfolio.reservation.identity.application;

import com.portfolio.reservation.audit.AuditService;
import com.portfolio.reservation.identity.api.AuthRequests;
import com.portfolio.reservation.identity.domain.*;
import com.portfolio.reservation.identity.infrastructure.*;
import com.portfolio.reservation.shared.api.ApiException;
import com.portfolio.reservation.users.api.UserView;
import com.portfolio.reservation.users.domain.*;
import com.portfolio.reservation.users.infrastructure.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final SessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final TokenService tokens;
    private final PasswordEncoder passwords;
    private final AuthProperties properties;
    private final Clock clock;
    private final AuditService audit;
    private final String dummyHash;

    public AuthService(UserRepository users, RoleRepository roles, SessionRepository sessions,
                       RefreshTokenRepository refreshTokens, TokenService tokens, PasswordEncoder passwords,
                       AuthProperties properties, Clock clock, AuditService audit) {
        this.users = users; this.roles = roles; this.sessions = sessions; this.refreshTokens = refreshTokens;
        this.tokens = tokens; this.passwords = passwords; this.properties = properties;
        this.clock = clock; this.audit = audit;
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public UserView register(AuthRequests.Register input, UUID requestId) {
        requirePassword(input.password());
        String email = normalizeEmail(input.email());
        if (users.findByEmail(email).isPresent())
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_UNAVAILABLE", "No se puede registrar ese correo.");
        var role = roles.findByName(Role.Name.CLIENTE).orElseThrow();
        var user = users.saveAndFlush(new User(input.name().strip(), email,
                passwords.encode(input.password()), role, clock.instant()));
        audit.record(user.getId(), "CLIENT_REGISTERED", "USER", user.getId(), requestId);
        return UserView.from(user);
    }

    @Transactional
    public Grant login(AuthRequests.Login input, UUID requestId) {
        User user = users.findIdByEmail(normalizeEmail(input.email())).flatMap(users::lockById).orElse(null);
        boolean matches = input.password().getBytes(StandardCharsets.UTF_8).length <= 72
                && passwords.matches(input.password(), user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !matches || !user.isActive()) {
            audit.authenticationFailure(requestId);
            throw unauthorized("INVALID_CREDENTIALS", "Correo o contraseña incorrectos.");
        }
        var session = sessions.save(new RefreshSession(user.getId(), user.getSecurityVersion(), clock.instant(),
                clock.instant().plus(properties.refreshTtl())));
        audit.record(user.getId(), "LOGIN_SUCCEEDED", "SESSION", session.getId(), requestId);
        return issue(user, session);
    }

    @Transactional(noRollbackFor = RefreshReplayException.class)
    public Grant refresh(String raw, UUID requestId) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) throw invalidSession();
        UUID sessionId = refreshTokens.findSessionIdByHash(TokenService.hash(raw)).orElseThrow(AuthService::invalidSession);
        UUID ownerId = sessions.findOwnerId(sessionId).orElseThrow(AuthService::invalidSession);
        // All mutations take user -> session locks, including password change and logout.
        var user = users.lockById(ownerId).orElseThrow(AuthService::invalidSession);
        var session = sessions.lockById(sessionId).orElseThrow(AuthService::invalidSession);
        // Load mutable entities only after locking, avoiding stale first-level cache state.
        var current = refreshTokens.findByTokenHash(TokenService.hash(raw)).orElseThrow(AuthService::invalidSession);
        if (!session.isValid(clock.instant()) || !user.isActive()
                || session.getSecurityVersion() != user.getSecurityVersion()) throw invalidSession();
        if (current.isConsumed()) {
            session.revoke(clock.instant());
            audit.record(user.getId(), "REFRESH_REPLAY", "SESSION", session.getId(), requestId);
            throw new RefreshReplayException();
        }
        current.consume(clock.instant());
        audit.record(user.getId(), "TOKEN_ROTATED", "SESSION", session.getId(), requestId);
        return issue(user, session);
    }

    @Transactional
    public void logout(String raw, UUID requestId) {
        if (raw == null || raw.length() > 200) return;
        var sessionId = refreshTokens.findSessionIdByHash(TokenService.hash(raw));
        if (sessionId.isEmpty()) return;
        UUID ownerId = sessions.findOwnerId(sessionId.get()).orElseThrow(AuthService::invalidSession);
        users.lockById(ownerId).orElseThrow(AuthService::invalidSession);
        var session = sessions.lockById(sessionId.get()).orElseThrow(AuthService::invalidSession);
        session.revoke(clock.instant());
        audit.record(session.getUserId(), "LOGOUT", "SESSION", session.getId(), requestId);
    }

    @Transactional
    public void changePassword(Jwt jwt, AuthRequests.PasswordChange input, UUID requestId) {
        var user = currentUserLocked(jwt);
        if (input.currentPassword().getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(input.currentPassword(), user.getPasswordHash()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INVALID", "La contraseña actual no es correcta.");
        requirePassword(input.newPassword());
        if (passwords.matches(input.newPassword(), user.getPasswordHash()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED", "Elige una contraseña diferente.");
        user.changePassword(passwords.encode(input.newPassword()), clock.instant());
        sessions.revokeAll(user.getId(), clock.instant());
        audit.record(user.getId(), "PASSWORD_CHANGED", "USER", user.getId(), requestId);
    }

    @Transactional(readOnly = true)
    public List<SessionView> ownSessions(Jwt jwt) {
        UUID current = UUID.fromString(jwt.getClaimAsString("sid"));
        return sessions.findActive(UUID.fromString(jwt.getSubject()), clock.instant()).stream()
                .map(s -> new SessionView(s.getId(), s.getCreatedAt(), s.getExpiresAt(), s.getId().equals(current)))
                .toList();
    }

    @Transactional
    public void revokeOwnSession(Jwt jwt, UUID sessionId, UUID requestId) {
        var user = currentUserLocked(jwt);
        var session = sessions.lockById(sessionId).filter(s -> s.getUserId().equals(user.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Sesión no encontrada."));
        session.revoke(clock.instant());
        audit.record(user.getId(), "SESSION_REVOKED", "SESSION", session.getId(), requestId);
    }

    private User currentUserLocked(Jwt jwt) {
        var user = users.lockById(UUID.fromString(jwt.getSubject())).orElseThrow(AuthService::invalidSession);
        var session = sessions.lockById(UUID.fromString(jwt.getClaimAsString("sid"))).orElseThrow(AuthService::invalidSession);
        Number version = jwt.getClaim("ver");
        if (!user.isActive() || version.longValue() != user.getSecurityVersion()
                || !user.getRole().getName().name().equals(jwt.getClaimAsString("role"))
                || !session.getUserId().equals(user.getId()) || !session.isValid(clock.instant())) throw invalidSession();
        return user;
    }

    private Grant issue(User user, RefreshSession session) {
        String raw = tokens.newRefreshToken();
        refreshTokens.save(new RefreshToken(session.getId(), TokenService.hash(raw), clock.instant()));
        return new Grant(tokens.accessToken(user, session), raw, session.getExpiresAt(),
                Math.min(properties.accessTtl().toSeconds(), Duration.between(clock.instant(), session.getExpiresAt()).toSeconds()),
                UserView.from(user));
    }

    private static void requirePassword(String password) {
        if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_POLICY", "Usa al menos 12 caracteres y como máximo 72 bytes UTF-8.");
    }

    public static String normalizeEmail(String email) { return email.strip().toLowerCase(Locale.ROOT); }
    private static ApiException unauthorized(String code, String message) { return new ApiException(HttpStatus.UNAUTHORIZED, code, message); }
    private static ApiException invalidSession() { return unauthorized("INVALID_SESSION", "La sesión no es válida. Inicia sesión de nuevo."); }

    public record Grant(String accessToken, String refreshToken, Instant refreshExpiresAt, long expiresIn, UserView user) {}
    public record SessionView(UUID id, Instant createdAt, Instant expiresAt, boolean current) {}
}
