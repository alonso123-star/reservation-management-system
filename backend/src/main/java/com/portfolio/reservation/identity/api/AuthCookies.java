package com.portfolio.reservation.identity.api;

import com.portfolio.reservation.identity.application.AuthProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.*;
import org.springframework.stereotype.Component;

@Component
public class AuthCookies {
    public static final String REFRESH = "RMS_REFRESH";
    private final AuthProperties properties;
    public AuthCookies(AuthProperties properties) { this.properties = properties; }

    public void set(HttpServletResponse response, String value, Duration age) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(REFRESH, value)
                .httpOnly(true).secure(properties.cookieSecure()).sameSite("Strict")
                .path("/api/v1/auth").maxAge(age).build().toString());
    }

    public void clear(HttpServletResponse response) { set(response, "", Duration.ZERO); }
}
