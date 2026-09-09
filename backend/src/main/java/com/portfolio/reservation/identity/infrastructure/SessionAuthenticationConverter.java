package com.portfolio.reservation.identity.infrastructure;

import com.portfolio.reservation.users.infrastructure.UserRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SessionAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final UserRepository users;
    private final SessionRepository sessions;
    private final Clock clock;

    public SessionAuthenticationConverter(UserRepository users, SessionRepository sessions, Clock clock) {
        this.users = users; this.sessions = sessions; this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            UUID userId = UUID.fromString(jwt.getSubject());
            UUID sessionId = UUID.fromString(jwt.getClaimAsString("sid"));
            var user = users.findById(userId).orElseThrow(SessionAuthenticationConverter::invalid);
            var session = sessions.findById(sessionId).orElseThrow(SessionAuthenticationConverter::invalid);
            Object version = jwt.getClaims().get("ver");
            if (!user.isActive() || !(version instanceof Number number)
                    || number.longValue() != user.getSecurityVersion()
                    || session.getSecurityVersion() != user.getSecurityVersion()
                    || !user.getRole().getName().name().equals(jwt.getClaimAsString("role"))
                    || !session.getUserId().equals(userId) || !session.isValid(clock.instant())) throw invalid();
            return new JwtAuthenticationToken(jwt,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().getName().name())),
                    userId.toString());
        } catch (IllegalArgumentException | NullPointerException invalidClaims) {
            throw invalid();
        }
    }

    private static OAuth2AuthenticationException invalid() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
    }
}
