package com.portfolio.reservation.identity.application;

import com.portfolio.reservation.identity.domain.RefreshSession;
import com.portfolio.reservation.users.domain.User;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TokenService(JwtEncoder encoder, AuthProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public String accessToken(User user, RefreshSession session) {
        Instant now = clock.instant();
        Instant expiry = now.plus(properties.accessTtl());
        if (session.getExpiresAt().isBefore(expiry)) expiry = session.getExpiresAt();
        var claims = JwtClaimsSet.builder().issuer(properties.issuer())
                .audience(List.of(properties.audience())).subject(user.getId().toString())
                .issuedAt(now).expiresAt(expiry).id(UUID.randomUUID().toString())
                .claim("sid", session.getId().toString()).claim("ver", user.getSecurityVersion())
                .claim("role", user.getRole().getName().name()).build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public String newRefreshToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
