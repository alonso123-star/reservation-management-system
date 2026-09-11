package com.portfolio.reservation;

import static org.assertj.core.api.Assertions.*;
import com.portfolio.reservation.reception.application.ReceptionPolicy;
import com.portfolio.reservation.reservations.domain.Reservation;
import java.net.*;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@Testcontainers @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ReceptionIT.TimeConfiguration.class)
class ReceptionIT {
    @Container static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18.6-alpine");
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary HotelClock hotelClock() { return new HotelClock(); }
    }
    static class HotelClock extends Clock {
        volatile Instant value = Instant.parse("2030-10-10T17:00:00Z");
        void at(String value) { this.value = Instant.parse(value); }
        @Override public Instant instant() { return value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(value, zone); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", DATABASE::getJdbcUrl); p.add("spring.datasource.username", DATABASE::getUsername);
        p.add("spring.datasource.password", DATABASE::getPassword);
        p.add("security.auth.jwt-secret", () -> Base64.getEncoder().encodeToString(new byte[32]));
        p.add("security.auth.cookie-secure", () -> false);
        p.add("hotel.time-zone", () -> "America/Lima"); p.add("hotel.reception.arrival-deadline", () -> "22:00");
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;
    @Autowired HotelClock clock;
    // Synthetic credentials only in this disposable PostgreSQL test database.
    static final String PASSWORD = "Reception synthetic fixture 123!";
    static String passwordHash;
    UUID reservation, room, type;
    final Map<String, UUID> users = new HashMap<>();
    final List<Client> clients = new ArrayList<>();
    @BeforeEach void prepare() {
        clock.at("2030-10-10T17:00:00Z");
        jdbc.execute("TRUNCATE idempotency_requests,refunds,payments,reservations,rooms,room_types,auth_rate_limits,audit_events,refresh_tokens,refresh_sessions,users");
        if (passwordHash == null) passwordHash = encoder.encode(PASSWORD);
        for (String name : List.of("client", "other", "employee", "admin")) {
            UUID id = UUID.randomUUID(); users.put(name, id);
            String role = name.equals("employee") ? "EMPLEADO" : name.equals("admin") ? "ADMIN" : "CLIENTE";
            jdbc.update("INSERT INTO users(id,role_id,name,email,password_hash,created_at,updated_at) SELECT ?,id,?,?,?,now(),now() FROM roles WHERE name=?", id, name, name + "@example.test", passwordHash, role);
        }
        type = UUID.randomUUID(); room = UUID.randomUUID(); reservation = UUID.randomUUID();
        jdbc.update("INSERT INTO room_types(id,name,description,capacity,base_price,created_at,updated_at) VALUES(?,'Reception Suite','Synthetic',2,125.50,now(),now())", type);
        jdbc.update("INSERT INTO rooms(id,room_type_id,code,floor,operational_status,created_at,updated_at) VALUES(?,?,'701',1,'ACTIVE',now(),now())", room, type);
        jdbc.update("INSERT INTO reservations(id,code,customer_id,created_by,room_id,check_in,check_out,guests,status,agreed_nightly_rate,total_amount,currency,created_at,updated_at) VALUES(?,?,?,?,?,'2030-10-10','2030-10-15',2,'CONFIRMED',125.50,627.50,'PEN',now(),now())",
                reservation, "R-" + reservation, users.get("client"), users.get("client"), room);
    }
    @AfterEach void closeClients() { clients.forEach(c -> c.http.close()); clients.clear(); }

    @ParameterizedTest @ValueSource(strings = {"employee", "admin"})
    void staffChecksInAndOutWithHistoricalTimestampsAndAtomicAudit(String role) throws Exception {
        approved(); var staff = login(role);
        var response = command(staff, "check-in", 0); assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var checkedIn = tree(response);
        assertThat(checkedIn.path("status").asText()).isEqualTo("CHECKED_IN");
        assertThat(Instant.parse(checkedIn.path("checkedInAt").asText())).isEqualTo(clock.instant());
        assertThat(checkedIn.path("checkedOutAt").isNull()).isTrue();
        assertAudit("RESERVATION_CHECKED_IN", users.get(role), response);
        clock.at("2030-10-10T17:01:00Z");
        var out = command(staff, "check-out", 1); assertThat(out.statusCode()).as(out.body()).isEqualTo(200);
        assertThat(tree(out).path("status").asText()).isEqualTo("CHECKED_OUT");
        assertThat(tree(out).path("checkedInAt")).isEqualTo(checkedIn.path("checkedInAt"));
        assertThat(Instant.parse(tree(out).path("checkedOutAt").asText())).isEqualTo(clock.instant());
        for (String f : List.of("checkIn", "checkOut", "agreedNightlyRate", "totalAmount", "currency")) assertThat(tree(out).path(f)).isEqualTo(checkedIn.path(f));
        assertThat(available("2030-10-12", "2030-10-15")).isZero();
        assertThat(count("payments")).isEqualTo(1); assertThat(count("refunds")).isZero();
        assertAudit("RESERVATION_CHECKED_OUT", users.get(role), out);
        assertThat(command(staff, "check-out", 2).statusCode()).isEqualTo(409);
        assertThat(auditCount("RESERVATION_CHECKED_OUT")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings = {"check-in", "check-out", "no-show"})
    void receptionRequiresStaffAndPreservesOwnershipAndSessionRules(String operation) throws Exception {
        var owner = login("client"); var other = login("other"); var staff = login("employee");
        assertThat(command(guest(), operation, 0).statusCode()).isEqualTo(401);
        assertThat(command(owner, operation, 0).statusCode()).isEqualTo(403);
        assertThat(command(other, operation, 0).statusCode()).isEqualTo(403);
        assertThat(staff.send("POST", "/reservations/" + UUID.randomUUID() + "/" + operation, Map.of("version", 0), null).statusCode()).isEqualTo(404);
        assertThat(other.get("/reservations/" + reservation).statusCode()).isEqualTo(404);
        assertThat(tree(owner.get("/reservations/" + reservation)).path("reception").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT version FROM reservations WHERE id=?", Long.class, reservation)).isZero();
        jdbc.update("UPDATE refresh_sessions SET revoked_at=? WHERE user_id=?", Timestamp.from(clock.instant()), users.get("employee"));
        assertThat(command(staff, operation, 0).statusCode()).isEqualTo(401);
    }
    @ParameterizedTest @ValueSource(strings = {"unpaid", "declined", "refunded"})
    void checkInRequiresTheExistingFullyPaidDefinition(String kind) throws Exception {
        if (kind.equals("declined")) insertPayment("DECLINED");
        if (kind.equals("refunded")) {
            UUID payment = approved();
            jdbc.update("INSERT INTO refunds(id,payment_id,amount,currency,reason,actor_id,created_at) VALUES(?,?,627.50,'PEN','Synthetic refunded fixture',?,now())", UUID.randomUUID(), payment, users.get("employee"));
        }
        var staff = login("employee"); assertProblem(command(staff, "check-in", 0), "RESERVATION_NOT_FULLY_PAID");
        assertThat(tree(staff.get("/reservations/" + reservation)).path("reception").path("canCheckIn").asBoolean()).isFalse();
        assertThat(state()).isEqualTo("CONFIRMED"); assertThat(available("2030-10-12", "2030-10-15")).isZero();
        assertThat(auditCount("RESERVATION_CHECKED_IN")).isZero();
    }
    @ParameterizedTest @CsvSource({
        "2030-10-10T04:59:59Z,false", "2030-10-10T05:00:00Z,true", "2030-10-12T17:00:00Z,true",
        "2030-10-15T04:59:59Z,true", "2030-10-15T05:00:00Z,false", "2030-10-16T17:00:00Z,false"})
    void checkInUsesHotelDateAndHalfOpenStay(String instant, boolean allowed) throws Exception {
        clock.at(instant); approved(); var response = command(login("employee"), "check-in", 0);
        if (allowed) assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        else assertProblem(response, "CHECK_IN_OUTSIDE_STAY");
    }
    @ParameterizedTest @CsvSource({
        "check-in,CHECKED_IN", "check-in,CHECKED_OUT", "check-in,CANCELLED", "check-in,NO_SHOW",
        "check-out,CONFIRMED", "check-out,CHECKED_OUT", "check-out,CANCELLED", "check-out,NO_SHOW",
        "no-show,CHECKED_IN", "no-show,CHECKED_OUT", "no-show,CANCELLED", "no-show,NO_SHOW"})
    void incompatibleTransitionsNeverRewriteHistory(String operation, String status) throws Exception {
        clock.at("2030-10-11T04:00:00Z"); seedState(status); approved();
        var before = jdbc.queryForMap("SELECT status,checked_in_at,checked_out_at,updated_at,version FROM reservations WHERE id=?", reservation);
        assertProblem(command(login("admin"), operation, 0), "INVALID_RESERVATION_STATE");
        assertThat(jdbc.queryForMap("SELECT status,checked_in_at,checked_out_at,updated_at,version FROM reservations WHERE id=?", reservation)).isEqualTo(before);
        assertThat(count("audit_events")).isEqualTo(1); // login only
    }
    @ParameterizedTest @CsvSource({"2030-10-10T17:00:00Z,false", "2030-10-11T03:00:00Z,false", "2030-10-11T03:00:01Z,true", "2030-10-16T17:00:00Z,true"})
    void noShowOnlyStrictlyAfterConfiguredArrivalDeadline(String instant, boolean allowed) throws Exception {
        clock.at(instant); approved(); var staff = login("admin"); var response = command(staff, "no-show", 0);
        if (!allowed) { assertProblem(response, "ARRIVAL_DEADLINE_NOT_PASSED"); return; }
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200); assertThat(state()).isEqualTo("NO_SHOW");
        assertThat(tree(response).path("checkedInAt").isNull()).isTrue(); assertThat(tree(response).path("checkedOutAt").isNull()).isTrue();
        assertThat(available("2030-10-12", "2030-10-15")).isEqualTo(1);
        assertThat(count("payments")).isEqualTo(1); assertThat(count("refunds")).isZero();
        assertAudit("RESERVATION_NO_SHOW", users.get("admin"), response);
        assertThat(command(staff, "no-show", 1).statusCode()).isEqualTo(409);
        assertThat(auditCount("RESERVATION_NO_SHOW")).isEqualTo(1);
    }
    @Test void policyIsExplicitConfigurableAndHandlesDstThroughTheHotelZone() {
        var r = new Reservation(users.get("client"), users.get("client"), null, LocalDate.parse("2030-03-10"), LocalDate.parse("2030-03-12"), 1,
                new java.math.BigDecimal("10"), new java.math.BigDecimal("20"), "PEN", clock.instant());
        var policy = new ReceptionPolicy(clock, "America/New_York", "02:30");
        // Local 02:30 does not exist on this DST day: java.time resolves it forward to 03:30.
        assertThat(policy.deadline(r)).isEqualTo(Instant.parse("2030-03-10T07:30:00Z"));
        assertThat(policy.pastDeadline(r, policy.deadline(r))).isFalse();
        assertThat(policy.pastDeadline(r, policy.deadline(r).plusSeconds(1))).isTrue();
        assertThatThrownBy(() -> new ReceptionPolicy(clock, "America/Lima", "25:00")).isInstanceOf(java.time.format.DateTimeParseException.class);
    }
    @Test void unknownFieldsInvalidVersionsAndOriginAreRejected() throws Exception {
        var staff = login("employee");
        for (String field : List.of("status", "checkedInAt", "checkedOutAt", "actor", "customerId", "fullyPaid", "hotelDate"))
            assertThat(staff.send("POST", path("check-in"), Map.of("version", 0, field, "manipulated"), null).statusCode()).isEqualTo(400);
        for (Object body : List.of(Map.of(), Map.of("version", -1), Map.of("version", 1.5)))
            assertThat(staff.send("POST", path("check-in"), body, null).statusCode()).isEqualTo(400);
        assertThat(staff.send("POST", path("check-in"), null, null).statusCode()).isEqualTo(400);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path("check-in")))
                .header("Authorization", "Bearer " + staff.access).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"version\":0}")).build();
        assertThat(staff.http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(state()).isEqualTo("CONFIRMED");
    }
    @ParameterizedTest @CsvSource({"check-in,RESERVATION_CHECKED_IN", "check-out,RESERVATION_CHECKED_OUT", "no-show,RESERVATION_NO_SHOW"})
    void auditFailureRollsBackTransitionAndTimestamps(String operation, String event) throws Exception {
        clock.at("2030-10-11T04:00:00Z"); approved(); if (operation.equals("check-out")) seedState("CHECKED_IN");
        var staff = login("employee"); var before = jdbc.queryForMap("SELECT status,version,checked_in_at,checked_out_at,updated_at FROM reservations WHERE id=?", reservation);
        jdbc.execute("CREATE FUNCTION fail_reception() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='" + event + "' THEN RAISE EXCEPTION 'Synthetic audit failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_reception BEFORE INSERT ON audit_events FOR EACH ROW EXECUTE FUNCTION fail_reception()");
        try {
            var response = command(staff, operation, 0); assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).doesNotContain("INSERT INTO", "Synthetic audit failure", "stackTrace");
            assertThat(jdbc.queryForMap("SELECT status,version,checked_in_at,checked_out_at,updated_at FROM reservations WHERE id=?", reservation)).isEqualTo(before);
            assertThat(auditCount(event)).isZero(); assertThat(count("refunds")).isZero();
        } finally { jdbc.execute("DROP TRIGGER fail_reception ON audit_events"); jdbc.execute("DROP FUNCTION fail_reception()"); }
    }
    @ParameterizedTest @CsvSource({
        "check-in,cancel,CHECKED_IN,RESERVATION_CHECKED_IN", "cancel,check-in,CANCELLED,RESERVATION_CANCELLED",
        "check-in,no-show,CHECKED_IN,RESERVATION_CHECKED_IN", "no-show,check-in,NO_SHOW,RESERVATION_NO_SHOW",
        "check-in,check-in,CHECKED_IN,RESERVATION_CHECKED_IN", "check-out,check-out,CHECKED_OUT,RESERVATION_CHECKED_OUT"})
    void incompatibleHttpTransactionsWaitOnTheSameReservationAndOnlyOneWins(String firstOp, String secondOp, String finalState, String event) throws Exception {
        clock.at("2030-10-11T04:00:00Z"); approved(); if (firstOp.equals("check-out")) seedState("CHECKED_IN");
        var firstClient = login("employee"); var secondClient = login("admin");
        jdbc.execute("CREATE FUNCTION pause_reception() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='" + event + "' THEN PERFORM pg_advisory_xact_lock(707070); END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER pause_reception BEFORE INSERT ON audit_events FOR EACH ROW EXECUTE FUNCTION pause_reception()");
        try (var gate = jdbc.getDataSource().getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            gate.createStatement().execute("SELECT pg_advisory_lock(707070)");
            var first = executor.submit(() -> command(firstClient, firstOp, 0)); Future<HttpResponse<String>> second = null;
            try {
                await(() -> jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND lower(wait_event)='advisory'", Integer.class) == 1);
                second = executor.submit(() -> command(secondClient, secondOp, 0));
                await(() -> jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock'", Integer.class) >= 2);
            } finally { gate.createStatement().execute("SELECT pg_advisory_unlock(707070)"); }
            assertThat(first.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(second.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
            assertThat(state()).isEqualTo(finalState); assertThat(auditCount(event)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action IN ('RESERVATION_CHECKED_IN','RESERVATION_CHECKED_OUT','RESERVATION_NO_SHOW','RESERVATION_CANCELLED')", Integer.class)).isEqualTo(1);
            var row = jdbc.queryForMap("SELECT version,checked_in_at,checked_out_at FROM reservations WHERE id=?", reservation);
            assertThat(row.get("version")).isEqualTo(1L);
            assertThat(row.get("checked_in_at") != null).isEqualTo(finalState.equals("CHECKED_IN") || finalState.equals("CHECKED_OUT"));
            assertThat(row.get("checked_out_at") != null).isEqualTo(finalState.equals("CHECKED_OUT"));
            assertThat(count("refunds")).isEqualTo(finalState.equals("CANCELLED") ? 1 : 0);
        } finally { jdbc.execute("DROP TRIGGER pause_reception ON audit_events"); jdbc.execute("DROP FUNCTION pause_reception()"); }
    }
    @Test void deterministicEndToEndRegistrationSearchBookingPaymentCheckInCheckOutAndNoShow() throws Exception {
        clock.at("2030-10-01T17:00:00Z"); var customer = guest();
        String email = "e2e-" + UUID.randomUUID() + "@example.test";
        assertThat(customer.send("POST", "/auth/register", Map.of("name", "Synthetic E2E guest", "email", email, "password", PASSWORD), null).statusCode()).isEqualTo(201);
        customer.signIn(email);
        var search = customer.get("/rooms/availability?checkIn=2030-10-20&checkOut=2030-10-25&guests=2");
        assertThat(search.statusCode()).isEqualTo(200); assertThat(tree(search).path("totalElements").asInt()).isEqualTo(1);
        var created = customer.send("POST", "/reservations", Map.of("roomId", room, "checkIn", "2030-10-20", "checkOut", "2030-10-25", "guests", 2), UUID.randomUUID().toString());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201); reservation = UUID.fromString(tree(created).path("id").asText());
        assertThat(customer.send("POST", path("payments"), Map.of(), UUID.randomUUID().toString()).statusCode()).isEqualTo(201);
        var paid = customer.send("POST", path("payments"), Map.of(), UUID.randomUUID().toString());
        assertThat(tree(paid).path("result").asText()).isEqualTo("APPROVED");
        clock.at("2030-10-20T17:00:00Z"); var staff = login("employee");
        var in = command(staff, "check-in", 0); assertThat(in.statusCode()).as(in.body()).isEqualTo(200);
        assertThat(tree(in).path("checkedInAt").isNull()).isFalse();
        var out = command(staff, "check-out", 1); assertThat(out.statusCode()).as(out.body()).isEqualTo(200);
        assertThat(tree(out).path("checkedOutAt").isNull()).isFalse();
        for (String field : List.of("checkIn", "checkOut", "agreedNightlyRate", "totalAmount", "currency")) assertThat(tree(out).path(field)).isEqualTo(tree(created).path(field));
        assertThat(available("2030-10-22", "2030-10-25")).isZero();
        customer.signIn(email);
        var another = customer.send("POST", "/reservations", Map.of("roomId", room, "checkIn", "2030-10-25", "checkOut", "2030-10-28", "guests", 2), UUID.randomUUID().toString());
        assertThat(another.statusCode()).isEqualTo(201); reservation = UUID.fromString(tree(another).path("id").asText());
        clock.at("2030-10-26T03:00:01Z"); staff = login("admin");
        assertThat(available("2030-10-26", "2030-10-28")).isZero();
        assertThat(command(staff, "no-show", 0).statusCode()).isEqualTo(200); assertThat(state()).isEqualTo("NO_SHOW");
        assertThat(available("2030-10-26", "2030-10-28")).isEqualTo(1);
        assertThat(count("refunds")).isZero();
    }
    @Test void openApiAndExistingV5SchemaDescribeOnlyTheReceptionExtension() throws Exception {
        var client = guest(); var response = client.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).GET().build(), HttpResponse.BodyHandlers.ofString());
        var docs = tree(response); assertThat(response.statusCode()).isEqualTo(200);
        for (String action : List.of("check-in", "check-out", "no-show")) {
            var route = docs.path("paths").path("/api/v1/reservations/{id}/" + action).path("post");
            for (String code : List.of("200", "400", "401", "403", "404", "409")) assertThat(route.path("responses").has(code)).isTrue();
            assertThat(route.path("security").toString()).contains("bearerAuth");
        }
        assertThat(docs.toString()).contains("RESERVATION_NOT_FULLY_PAID", "ARRIVAL_DEADLINE_NOT_PASSED", "/admin/dashboard", "/admin/audit-events");
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class)).containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='ex_reservations_room_stay'", String.class))
                .contains("CONFIRMED", "CHECKED_IN", "CHECKED_OUT").doesNotContain("NO_SHOW", "CANCELLED");
    }
    private UUID approved() { return insertPayment("APPROVED"); }
    private UUID insertPayment(String result) {
        UUID id = UUID.randomUUID(); jdbc.update("INSERT INTO payments(id,reservation_id,amount,currency,result,simulated_reference,actor_id,created_at) VALUES(?,?,627.50,'PEN',?,?,?,now())", id, reservation, result, "SIM-P-" + id, users.get("client")); return id;
    }
    private void seedState(String state) {
        jdbc.update("UPDATE reservations SET status=?,checked_in_at=?,checked_out_at=?,cancelled_by=?,cancelled_at=?,cancellation_reason=? WHERE id=?", state,
                state.equals("CHECKED_IN") || state.equals("CHECKED_OUT") ? Timestamp.from(Instant.parse("2030-10-10T05:00:00Z")) : null,
                state.equals("CHECKED_OUT") ? Timestamp.from(Instant.parse("2030-10-10T06:00:00Z")) : null,
                state.equals("CANCELLED") ? users.get("employee") : null, state.equals("CANCELLED") ? Timestamp.from(clock.instant()) : null,
                state.equals("CANCELLED") ? "Synthetic state fixture" : null, reservation);
    }
    private void assertAudit(String event, UUID actor, HttpResponse<String> response) {
        var row = jdbc.queryForMap("SELECT actor_id,resource_id,occurred_at,request_id FROM audit_events WHERE action=?", event);
        assertThat(row.get("actor_id")).isEqualTo(actor); assertThat(row.get("resource_id")).isEqualTo(reservation);
        assertThat(((Timestamp) row.get("occurred_at")).toInstant()).isEqualTo(clock.instant());
        assertThat(row.get("request_id").toString()).isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
    }
    private void await(Supplier<Boolean> condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < until) { if (condition.get()) return; Thread.sleep(25); }
        fail("Expected real PostgreSQL concurrent lock wait");
    }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private int auditCount(String event) { return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action=?", Integer.class, event); }
    private String state() { return jdbc.queryForObject("SELECT status FROM reservations WHERE id=?", String.class, reservation); }
    private int available(String in, String out) throws Exception { return tree(guest().get("/rooms/availability?checkIn=" + in + "&checkOut=" + out + "&guests=2")).path("totalElements").asInt(); }
    private String path(String action) { return "/reservations/" + reservation + "/" + action; }
    private HttpResponse<String> command(Client client, String action, long version) throws Exception { return client.send("POST", path(action), action.equals("cancel") ? Map.of("version", version, "reason", "Synthetic cancellation") : Map.of("version", version), null); }
    private void assertProblem(HttpResponse<String> response, String code) { assertThat(response.statusCode()).as(response.body()).isEqualTo(409); assertThat(tree(response).path("code").asText()).isEqualTo(code); }
    private JsonNode tree(HttpResponse<String> response) { return json.readTree(response.body()); }
    private Client guest() { var client = new Client(); clients.add(client); return client; }
    private Client login(String name) throws Exception { var c = guest(); c.signIn(name + "@example.test"); return c; }
    private class Client {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(10)).build();
        String access;
        void signIn(String email) throws Exception {
            access = null; var response = send("POST", "/auth/login", Map.of("email", email, "password", PASSWORD), null);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200); access = tree(response).path("accessToken").asText();
        }
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
        HttpResponse<String> send(String method, String path, Object body, String key) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(40)).header("Content-Type", "application/json");
            if (access != null) request.header("Authorization", "Bearer " + access);
            if (key != null) request.header("Idempotency-Key", key);
            if (!method.equals("GET")) {
                request.header("Origin", "http://localhost:3000");
                if (access == null) { var csrf = tree(get("/auth/csrf")); request.header(csrf.path("headerName").asText(), csrf.path("token").asText()); }
            }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
