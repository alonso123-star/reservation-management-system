package com.portfolio.reservation.identity.infrastructure;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.portfolio.reservation.identity.application.AuthProperties;
import com.portfolio.reservation.shared.api.ApiErrors;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfiguration {
    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecretKeySpec jwtKey(AuthProperties properties) {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(properties.jwtSecret()); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("JWT_SECRET_BASE64 must be valid Base64"); }
        if (bytes.length < 32) throw new IllegalArgumentException("JWT key must contain at least 256 bits");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKeySpec key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }

    @Bean
    JwtDecoder jwtDecoder(SecretKeySpec key, AuthProperties properties, Clock clock) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(properties.audience())
                && jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps, new JwtIssuerValidator(properties.issuer()), audience));
        return decoder;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, AuthProperties properties,
                                 SessionAuthenticationConverter converter, ApiErrors errors) throws Exception {
        var csrf = new CookieCsrfTokenRepository();
        csrf.setCookieCustomizer(cookie -> cookie.httpOnly(true).secure(properties.cookieSecure())
                .sameSite("Strict").path("/"));
        return http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                // Protect cookie-based operations, including login/registration. Resource Server
                // exempts explicit bearer requests; Origin validation still applies to every mutation.
                .csrf(config -> config.csrfTokenRepository(csrf))
                .addFilterBefore(new RequestOriginFilter(properties, errors), CsrfFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/room-types", "/api/v1/room-types/{id}",
                                "/api/v1/rooms", "/api/v1/rooms/availability", "/api/v1/rooms/{id}").permitAll()
                        .requestMatchers("/api/v1/auth/**", "/api/v1/system/**", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> errors.write(request, response,
                                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Inicia sesión para continuar."))
                        .accessDeniedHandler((request, response, exception) -> errors.write(request, response,
                                HttpStatus.FORBIDDEN,
                                exception instanceof CsrfException ? "CSRF_INVALID" : "ACCESS_DENIED",
                                exception instanceof CsrfException ? "La protección de la petición no es válida." : "No tienes permiso.")))
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, exception) -> errors.write(request, response,
                                HttpStatus.UNAUTHORIZED, "INVALID_SESSION", "La sesión no es válida. Inicia sesión de nuevo.")))
                .build();
    }

    private static class RequestOriginFilter extends OncePerRequestFilter {
        private final AuthProperties properties;
        private final ApiErrors errors;
        RequestOriginFilter(AuthProperties properties, ApiErrors errors) { this.properties = properties; this.errors = errors; }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            response.setHeader("X-Request-Id", ApiErrors.requestId(request).toString());
            if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
                    && !properties.allowedOrigins().contains(request.getHeader("Origin"))) {
                errors.write(request, response, HttpStatus.FORBIDDEN, "ORIGIN_NOT_ALLOWED", "Origen de petición no permitido.");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
