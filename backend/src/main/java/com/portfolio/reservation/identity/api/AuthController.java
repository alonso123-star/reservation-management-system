package com.portfolio.reservation.identity.api;

import com.portfolio.reservation.identity.application.*;
import com.portfolio.reservation.shared.api.ApiErrors;
import com.portfolio.reservation.users.api.UserView;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.time.*;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {
    private static final String COOKIE = "RMS_REFRESH";
    private final AuthService service;
    private final AuthRateLimiter limiter;
    private final AuthProperties properties;
    private final Clock clock;
    private final AuthCookies cookies;

    public AuthController(AuthService service, AuthRateLimiter limiter, AuthProperties properties, Clock clock, AuthCookies cookies) {
        this.service = service; this.limiter = limiter; this.properties = properties; this.clock = clock;
        this.cookies = cookies;
    }

    @GetMapping("/csrf")
    public CsrfView csrf(CsrfToken csrfToken) { return new CsrfView(csrfToken.getHeaderName(), csrfToken.getToken()); }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView register(@Valid @RequestBody AuthRequests.Register body, HttpServletRequest request) {
        limiter.check("register:ip:" + request.getRemoteAddr(), 20, Duration.ofHours(1));
        return service.register(body, ApiErrors.requestId(request));
    }

    @PostMapping("/login")
    public AccessView login(@Valid @RequestBody AuthRequests.Login body,
                            HttpServletRequest request, HttpServletResponse response) {
        limiter.check("login:ip:" + request.getRemoteAddr(), 100, Duration.ofMinutes(15));
        limiter.check("login:email:" + AuthService.normalizeEmail(body.email()), 10, Duration.ofMinutes(15));
        return grant(service.login(body, ApiErrors.requestId(request)), response);
    }

    @PostMapping("/refresh")
    public AccessView refresh(@CookieValue(name = COOKIE, required = false) String refreshToken,
                              HttpServletRequest request, HttpServletResponse response) {
        try { return grant(service.refresh(refreshToken, ApiErrors.requestId(request)), response); }
        catch (com.portfolio.reservation.shared.api.ApiException invalid) {
            cookies.clear(response);
            throw invalid;
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name = COOKIE, required = false) String refreshToken,
                       @AuthenticationPrincipal Jwt jwt, HttpServletRequest request, HttpServletResponse response) {
        if (jwt != null) service.revokeOwnSession(jwt, UUID.fromString(jwt.getClaimAsString("sid")), ApiErrors.requestId(request));
        service.logout(refreshToken, ApiErrors.requestId(request));
        cookies.clear(response);
    }

    private AccessView grant(AuthService.Grant grant, HttpServletResponse response) {
        cookies.set(response, grant.refreshToken(), Duration.between(clock.instant(), grant.refreshExpiresAt()));
        return new AccessView(grant.accessToken(), "Bearer", grant.expiresIn(), grant.user());
    }

    public record AccessView(String accessToken, String tokenType, long expiresIn, UserView user) {}
    public record CsrfView(String headerName, String token) {}
}
