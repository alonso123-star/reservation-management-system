package com.portfolio.reservation.users.api;

import com.portfolio.reservation.identity.api.AuthRequests;
import com.portfolio.reservation.identity.api.AuthCookies;
import com.portfolio.reservation.identity.application.AuthService;
import com.portfolio.reservation.shared.api.*;
import com.portfolio.reservation.users.infrastructure.UserRepository;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users")
@SecurityRequirement(name = "bearerAuth")
public class UserController {
    private final UserRepository users;
    private final AuthService auth;
    private final AuthCookies cookies;

    public UserController(UserRepository users, AuthService auth, AuthCookies cookies) {
        this.users = users; this.auth = auth; this.cookies = cookies;
    }

    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal Jwt jwt) { return find(UUID.fromString(jwt.getSubject())); }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserView byId(@PathVariable UUID id) { return find(id); }

    @PutMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AuthRequests.PasswordChange body,
                         HttpServletRequest request, HttpServletResponse response) {
        auth.changePassword(jwt, body, ApiErrors.requestId(request));
        cookies.clear(response);
    }

    @GetMapping("/me/sessions")
    public List<AuthService.SessionView> sessions(@AuthenticationPrincipal Jwt jwt) { return auth.ownSessions(jwt); }

    @DeleteMapping("/me/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest request) {
        auth.revokeOwnSession(jwt, id, ApiErrors.requestId(request));
    }

    private UserView find(UUID id) {
        return users.findById(id).map(UserView::from).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Usuario no encontrado."));
    }
}
