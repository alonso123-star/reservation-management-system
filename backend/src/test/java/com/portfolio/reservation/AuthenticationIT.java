package com.portfolio.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.portfolio.reservation.identity.application.TokenService;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuthenticationIT.TimeConfiguration.class)
class AuthenticationIT {
    private static final String PASSWORD = "Integration test passphrase 123!";
    private static final String NEW_PASSWORD = "Another integration passphrase 456!";

    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18.6-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("security.auth.jwt-secret", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("security.auth.cookie-secure", () -> false);
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtEncoder encoder;
    @Autowired JwtDecoder decoder;
    @Autowired MutableClock clock;
    final List<Browser> clients = new ArrayList<>();

    @BeforeEach
    void reset() {
        clock.reset();
        jdbc.execute("TRUNCATE idempotency_requests, refunds, payments, reservations, auth_rate_limits, audit_events, refresh_tokens, refresh_sessions, users");
    }

    @AfterEach
    void close() {
        clients.forEach(browser -> browser.client.close());
        clients.clear();
        clock.reset();
    }

    @Test
    void registrationNormalizesEmailHashesPasswordAndOnlyCreatesClients() throws Exception {
        var browser = browser();
        var registration = browser.register("Client@Example.test");
        assertThat(registration.statusCode()).isEqualTo(201);
        var view = tree(registration);
        assertThat(view.get("role").asText()).isEqualTo("CLIENTE");
        assertThat(view.get("email").asText()).isEqualTo("client@example.test");
        assertThat(view.has("passwordHash")).isFalse();
        String hash = jdbc.queryForObject("SELECT password_hash FROM users", String.class);
        assertThat(hash.equals(PASSWORD)).isFalse();
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='CLIENT_REGISTERED'", Integer.class)).isEqualTo(1);
        var login = browser.login("client@example.test", PASSWORD);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(tree(browser.get("/users/me")).get("id").asText()).isEqualTo(view.get("id").asText());
    }

    @Test
    void rejectsInjectedRolesAndUnknownSecurityFields() throws Exception {
        var browser = browser();
        for (String field : List.of("role", "roleId", "active", "securityVersion", "id")) {
            var body = new HashMap<String, Object>(Map.of("name", "Client", "email", "attack@example.test", "password", PASSWORD));
            body.put(field, "ADMIN");
            assertThat(browser.send("POST", "/auth/register", body).statusCode()).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }

    @Test
    void validatesInputAndRejectsCaseInsensitiveDuplicateEmails() throws Exception {
        var browser = browser();
        assertThat(browser.register("client@example.test").statusCode()).isEqualTo(201);
        assertThat(browser.register("CLIENT@example.test").statusCode()).isEqualTo(409);
        assertThat(browser.send("POST", "/auth/register", Map.of("name", "Client", "email", "invalid", "password", PASSWORD)).statusCode()).isEqualTo(400);
        assertThat(browser.send("POST", "/auth/register", Map.of("name", "Client", "email", "other@example.test", "password", "short")).statusCode()).isEqualTo(400);
        assertThat(browser.send("POST", "/auth/register", Map.of("name", "Client", "email", "other@example.test", "password", "é".repeat(40))).statusCode()).isEqualTo(400);
    }

    @Test
    void failedLoginIsGenericAndAuditedDespiteRollback() throws Exception {
        var browser = browser();
        browser.register("client@example.test");
        var existing = browser.login("client@example.test", "incorrect passphrase");
        var missing = browser.login("missing@example.test", "incorrect passphrase");
        assertThat(existing.statusCode()).isEqualTo(401);
        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(tree(existing).get("code").asText()).isEqualTo(tree(missing).get("code").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='LOGIN_FAILED'", Integer.class)).isEqualTo(2);
    }

    @Test
    void usesShortLivedJwtAndHttpOnlyScopedRefreshCookie() throws Exception {
        var browser = signedIn("client@example.test");
        var jwt = decoder.decode(browser.access);
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getAudience()).containsExactly("reservation-web");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("CLIENTE");
        var login = browser.login("client@example.test", PASSWORD);
        String cookie = login.headers().allValues("Set-Cookie").stream().filter(v -> v.startsWith("RMS_REFRESH=")).findFirst().orElseThrow();
        assertThat(cookie.contains("HttpOnly")).isTrue();
        assertThat(cookie.contains("SameSite=Strict")).isTrue();
        assertThat(cookie.contains("Path=/api/v1/auth")).isTrue();
        assertThat(tree(login).has("refreshToken")).isFalse();
        assertThat(login.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }

    @Test
    void rotatesRefreshHashesAndReplayRevokesTheEntireFamily() throws Exception {
        var browser = signedIn("client@example.test");
        String old = browser.refreshValue();
        String oldAccess = browser.access;
        var rotated = browser.send("POST", "/auth/refresh", null);
        assertThat(rotated.statusCode()).isEqualTo(200);
        browser.access = tree(rotated).get("accessToken").asText();
        assertThat(browser.refreshValue().equals(old)).isFalse();
        var hashes = jdbc.queryForList("SELECT token_hash FROM refresh_tokens", String.class);
        assertThat(hashes).allMatch(h -> h.length() == 64);
        assertThat(hashes.contains(old) || hashes.contains(browser.refreshValue())).isFalse();
        assertThat(browser.getWithToken("/users/me", oldAccess).statusCode()).isEqualTo(200);

        var replay = browser();
        replay.seedRefresh(old);
        assertThat(replay.send("POST", "/auth/refresh", null).statusCode()).isEqualTo(401);
        assertThat(browser.get("/users/me").statusCode()).isEqualTo(401);
        assertThat(browser.send("POST", "/auth/refresh", null).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='REFRESH_REPLAY'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_sessions WHERE revoked_at IS NOT NULL", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentRefreshesHaveOneWinnerAndFailClosedOnReplay() throws Exception {
        var original = signedIn("client@example.test");
        var first = browser();
        var second = browser();
        first.seedRefresh(original.refreshValue());
        second.seedRefresh(original.refreshValue());
        var responses = concurrently(() -> first.send("POST", "/auth/refresh", null),
                () -> second.send("POST", "/auth/refresh", null));
        assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 401);
        assertThat(original.get("/users/me").statusCode()).isEqualTo(401);
        var winner = responses.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow();
        assertThat(original.getWithToken("/users/me", tree(winner).get("accessToken").asText()).statusCode()).isEqualTo(401);
    }

    @Test
    void logoutRevokesAccessAndRefreshAndIsIdempotent() throws Exception {
        var browser = signedIn("client@example.test");
        String oldRefresh = browser.refreshValue();
        var response = browser.send("POST", "/auth/logout", null);
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.headers().allValues("Set-Cookie").stream().anyMatch(v -> v.contains("RMS_REFRESH=") && v.contains("Max-Age=0"))).isTrue();
        assertThat(browser.get("/users/me").statusCode()).isEqualTo(401);
        browser.seedRefresh(oldRefresh);
        assertThat(browser.send("POST", "/auth/refresh", null).statusCode()).isEqualTo(401);
        assertThat(browser.send("POST", "/auth/logout", null).statusCode()).isEqualTo(204);
    }

    @Test
    void passwordChangeRevokesEverySessionAndRequiresCurrentPassword() throws Exception {
        var first = signedIn("client@example.test");
        var second = browser();
        second.login("client@example.test", PASSWORD);
        assertThat(first.send("PUT", "/users/me/password", Map.of("currentPassword", "incorrect passphrase", "newPassword", NEW_PASSWORD)).statusCode()).isEqualTo(400);
        assertThat(first.send("PUT", "/users/me/password", Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD)).statusCode()).isEqualTo(204);
        assertThat(first.get("/users/me").statusCode()).isEqualTo(401);
        assertThat(second.get("/users/me").statusCode()).isEqualTo(401);
        assertThat(second.send("POST", "/auth/refresh", null).statusCode()).isEqualTo(401);
        assertThat(first.login("client@example.test", PASSWORD).statusCode()).isEqualTo(401);
        assertThat(first.login("client@example.test", NEW_PASSWORD).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT security_version FROM users", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentPasswordChangeAndRefreshLeaveNoValidOldSession() throws Exception {
        var owner = signedIn("client@example.test");
        var renewal = browser();
        renewal.seedRefresh(owner.refreshValue());
        var results = concurrently(
                () -> owner.send("PUT", "/users/me/password", Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD)),
                () -> renewal.send("POST", "/auth/refresh", null));
        assertThat(results.get(0).statusCode()).isEqualTo(204);
        assertThat(results.get(1).statusCode()).isIn(200, 401);
        if (results.get(1).statusCode() == 200)
            assertThat(owner.getWithToken("/users/me", tree(results.get(1)).get("accessToken").asText()).statusCode()).isEqualTo(401);
        assertThat(owner.get("/users/me").statusCode()).isEqualTo(401);
    }

    @Test
    void enforcesRolesAndImmediatelyRejectsOutdatedRoleClaims() throws Exception {
        var target = signedIn("target@example.test");
        UUID targetId = UUID.fromString(tree(target.get("/users/me")).get("id").asText());
        var actor = signedIn("actor@example.test");
        assertThat(actor.get("/users/" + targetId).statusCode()).isEqualTo(403);
        setRole("actor@example.test", "EMPLEADO");
        assertThat(actor.get("/users/me").statusCode()).isEqualTo(401);
        actor.login("actor@example.test", PASSWORD);
        assertThat(tree(actor.get("/users/me")).get("role").asText()).isEqualTo("EMPLEADO");
        assertThat(actor.get("/users/" + targetId).statusCode()).isEqualTo(403);
        setRole("actor@example.test", "ADMIN");
        actor.login("actor@example.test", PASSWORD);
        assertThat(actor.get("/users/" + targetId).statusCode()).isEqualTo(200);
        assertThat(actor.get("/users/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
    }

    @Test
    void sessionsAreVisibleAndRevocableOnlyByTheirOwner() throws Exception {
        var owner = signedIn("owner@example.test");
        var other = signedIn("other@example.test");
        UUID ownSession = UUID.fromString(tree(owner.get("/users/me/sessions")).get(0).get("id").asText());
        assertThat(tree(other.get("/users/me/sessions")).size()).isEqualTo(1);
        assertThat(other.send("DELETE", "/users/me/sessions/" + ownSession, null).statusCode()).isEqualTo(404);
        assertThat(owner.get("/users/me").statusCode()).isEqualTo(200);
        assertThat(owner.send("DELETE", "/users/me/sessions/" + ownSession, null).statusCode()).isEqualTo(204);
        assertThat(owner.get("/users/me").statusCode()).isEqualTo(401);
        assertThat(other.get("/users/me").statusCode()).isEqualTo(200);
    }

    @Test
    void expiredAccessCanRefreshButExpiredRefreshCannot() throws Exception {
        var browser = signedIn("client@example.test");
        clock.advance(Duration.ofMinutes(16));
        assertThat(browser.get("/users/me").statusCode()).isEqualTo(401);
        var refresh = browser.send("POST", "/auth/refresh", null);
        assertThat(refresh.statusCode()).isEqualTo(200);
        browser.access = tree(refresh).get("accessToken").asText();
        assertThat(browser.get("/users/me").statusCode()).isEqualTo(200);
        clock.advance(Duration.ofDays(8));
        assertThat(browser.send("POST", "/auth/refresh", null).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsDisabledUsersAndChangedSecurityVersionForBothTokenTypes() throws Exception {
        var disabled = signedIn("disabled@example.test");
        jdbc.update("UPDATE users SET active=false WHERE email=?", "disabled@example.test");
        assertThat(disabled.get("/users/me").statusCode()).isEqualTo(401);
        assertThat(disabled.send("POST", "/auth/refresh", null).statusCode()).isEqualTo(401);
        assertThat(disabled.login("disabled@example.test", PASSWORD).statusCode()).isEqualTo(401);
        var changed = signedIn("changed@example.test");
        jdbc.update("UPDATE users SET security_version=security_version+1 WHERE email=?", "changed@example.test");
        assertThat(changed.get("/users/me").statusCode()).isEqualTo(401);
        assertThat(changed.send("POST", "/auth/refresh", null).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsWrongIssuerAudienceSignatureAndInjectedRoleClaims() throws Exception {
        var browser = signedIn("client@example.test");
        var valid = decoder.decode(browser.access);
        for (var override : List.of(Map.<String, Object>of("iss", "other-issuer"),
                Map.<String, Object>of("aud", List.of("other-audience")), Map.<String, Object>of("role", "ADMIN"),
                Map.<String, Object>of("sid", UUID.randomUUID().toString()))) {
            assertThat(browser.getWithToken("/users/me", sign(valid, override, encoder)).statusCode()).isEqualTo(401);
        }
        byte[] anotherKey = new byte[32];
        Arrays.fill(anotherKey, (byte) 7);
        var otherEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(anotherKey, "HmacSHA256")));
        assertThat(browser.getWithToken("/users/me", sign(valid, Map.of(), otherEncoder)).statusCode()).isEqualTo(401);
    }

    @Test
    void requiresCsrfAndTrustedOriginWithoutRevokingTheVictimSession() throws Exception {
        var browser = signedIn("client@example.test");
        assertThat(browser.raw("POST", "/auth/logout", null, false, "http://localhost:3000", null).statusCode()).isEqualTo(403);
        assertThat(browser.raw("POST", "/auth/logout", null, true, "https://attacker.example", null).statusCode()).isEqualTo(403);
        assertThat(browser.raw("POST", "/auth/logout", null, true, null, null).statusCode()).isEqualTo(403);
        assertThat(browser.get("/users/me").statusCode()).isEqualTo(200);
    }

    @Test
    void limitsLoginAttemptsInTheDatabase() throws Exception {
        var browser = browser();
        for (int i = 0; i < 10; i++)
            assertThat(browser.login("missing@example.test", "incorrect passphrase").statusCode()).isEqualTo(401);
        assertThat(browser.login("missing@example.test", "incorrect passphrase").statusCode()).isEqualTo(429);
        assertThat(jdbc.queryForObject("SELECT attempts FROM auth_rate_limits WHERE bucket_hash=?",
                Integer.class, TokenService.hash("login:email:missing@example.test"))).isEqualTo(11);
    }

    private void setRole(String email, String role) {
        jdbc.update("UPDATE users SET role_id=(SELECT id FROM roles WHERE name=?), security_version=security_version+1 WHERE email=?", role, email);
    }

    private String sign(Jwt original, Map<String, Object> changes, JwtEncoder signer) {
        var claims = JwtClaimsSet.builder().claims(map -> map.putAll(original.getClaims()));
        changes.forEach(claims::claim);
        return signer.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }

    private List<HttpResponse<String>> concurrently(Callable<HttpResponse<String>> first,
                                                    Callable<HttpResponse<String>> second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var a = executor.submit(() -> { gate.await(); return first.call(); });
            var b = executor.submit(() -> { gate.await(); return second.call(); });
            gate.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }

    private Browser signedIn(String email) throws Exception {
        var browser = browser();
        assertThat(browser.register(email).statusCode()).isEqualTo(201);
        assertThat(browser.login(email, PASSWORD).statusCode()).isEqualTo(200);
        return browser;
    }

    private Browser browser() { var browser = new Browser(); clients.add(browser); return browser; }
    private JsonNode tree(HttpResponse<String> response) { return json.readTree(response.body()); }

    class Browser {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
        String access;

        HttpResponse<String> register(String email) throws Exception {
            return send("POST", "/auth/register", Map.of("name", "Test Client", "email", email, "password", PASSWORD));
        }
        HttpResponse<String> login(String email, String password) throws Exception {
            var result = send("POST", "/auth/login", Map.of("email", email, "password", password));
            if (result.statusCode() == 200) access = tree(result).get("accessToken").asText();
            return result;
        }
        HttpResponse<String> get(String path) throws Exception { return getWithToken(path, access); }
        HttpResponse<String> getWithToken(String path, String token) throws Exception { return raw("GET", path, null, false, null, token); }
        HttpResponse<String> send(String method, String path, Object body) throws Exception {
            return raw(method, path, body, true, "http://localhost:3000", path.startsWith("/auth/") ? null : access);
        }
        HttpResponse<String> raw(String method, String path, Object body, boolean csrf, String origin, String token) throws Exception {
            var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15));
            if (csrf) {
                var value = tree(client.send(HttpRequest.newBuilder(uri("/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString()));
                request.header(value.get("headerName").asText(), value.get("token").asText());
            }
            if (origin != null) request.header("Origin", origin);
            if (token != null) request.header("Authorization", "Bearer " + token);
            request.header("Content-Type", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        String refreshValue() {
            return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("RMS_REFRESH")).findFirst().orElseThrow().getValue();
        }
        void seedRefresh(String value) {
            cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("RMS_REFRESH")).toList()
                    .forEach(c -> cookies.getCookieStore().remove(uri("/auth/refresh"), c));
            var cookie = new HttpCookie("RMS_REFRESH", value);
            cookie.setVersion(0);
            cookie.setPath("/api/v1/auth");
            cookies.getCookieStore().add(uri("/auth/refresh"), cookie);
        }
        URI uri(String path) { return URI.create("http://localhost:" + port + "/api/v1" + path); }
    }

    static class MutableClock extends Clock {
        private volatile Duration offset = Duration.ZERO;
        void advance(Duration duration) { offset = offset.plus(duration); }
        void reset() { offset = Duration.ZERO; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.now().plus(offset); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }
}
