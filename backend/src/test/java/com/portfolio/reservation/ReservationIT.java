package com.portfolio.reservation;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ReservationIT.TestClock.class)
class ReservationIT {
    @Container static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18.6-alpine");
    @TestConfiguration static class TestClock {
        @Bean @Primary Clock reservationClock() { return Clock.fixed(Instant.parse("2027-01-01T03:00:00Z"), ZoneOffset.UTC); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", DATABASE::getJdbcUrl);
        p.add("spring.datasource.username", DATABASE::getUsername);
        p.add("spring.datasource.password", DATABASE::getPassword);
        p.add("security.auth.jwt-secret", () -> Base64.getEncoder().encodeToString(new byte[32]));
        p.add("security.auth.cookie-secure", () -> false);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;
    // Synthetic credentials only in this disposable Testcontainers database.
    static final String PASSWORD = "Reservation integration fixture 123!";
    static String passwordHash;
    UUID room, type;
    Map<String, UUID> users;
    List<Client> clients = new ArrayList<>();
    @BeforeEach void prepare() {
        jdbc.execute("TRUNCATE idempotency_requests,refunds,payments,reservations,rooms,room_types,auth_rate_limits,audit_events,refresh_tokens,refresh_sessions,users");
        if (passwordHash == null) passwordHash = encoder.encode(PASSWORD);
        users = new HashMap<>();
        for (String name : List.of("a", "b", "employee", "admin")) {
            String role = name.equals("employee") ? "EMPLEADO" : name.equals("admin") ? "ADMIN" : "CLIENTE";
            var id = UUID.randomUUID(); users.put(name, id);
            jdbc.update("INSERT INTO users(id,role_id,name,email,password_hash,active,created_at,updated_at) SELECT ?,id,?,?,?,true,now(),now() FROM roles WHERE name=?",
                    id, name, name + "@example.test", passwordHash, role);
        }
        type = UUID.randomUUID(); room = UUID.randomUUID();
        jdbc.update("INSERT INTO room_types(id,name,description,capacity,base_price,active,created_at,updated_at) VALUES(?,'Suite','Test',2,125.50,true,now(),now())", type);
        jdbc.update("INSERT INTO rooms(id,room_type_id,code,floor,operational_status,active,created_at,updated_at) VALUES(?,?,'101',1,'ACTIVE',true,now(),now())", room, type);
    }
    @AfterEach void close() { clients.forEach(c -> c.http.close()); }

    @Test void customerCreationAndIntegratedAvailabilityHistoryCancellationFlow() throws Exception {
        var a = login("a");
        assertThat(available("2027-10-10", "2027-10-12")).isEqualTo(1);
        var created = create(a, body(), UUID.randomUUID());
        assertThat(created.path("customerId").asText()).isEqualTo(users.get("a").toString());
        assertThat(created.path("createdBy").asText()).isEqualTo(users.get("a").toString());
        assertThat(created.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(created.path("code").asText()).matches("R-[A-F0-9]{32}");
        assertThat(created.path("totalAmount").decimalValue()).isEqualByComparingTo("251.00");
        assertThat(created.path("agreedNightlyRate").decimalValue()).isEqualByComparingTo("125.50");
        assertThat(created.path("nights").asInt()).isEqualTo(2);
        assertThat(created.path("currency").asText()).isEqualTo("PEN");
        assertThat(available("2027-10-10", "2027-10-12")).isZero();
        assertThat(tree(a.get("/reservations")).path("totalElements").asInt()).isEqualTo(1);
        var detail = a.get("/reservations/" + id(created)); assertThat(detail.statusCode()).isEqualTo(200);
        var cancelled = a.send("POST", "/reservations/" + id(created) + "/cancel", Map.of("version", 0, "reason", "Cambio de planes"), null);
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        assertThat(tree(cancelled).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(tree(cancelled).path("version").asLong()).isEqualTo(1);
        assertThat(tree(cancelled).path("cancelledBy").asText()).isEqualTo(users.get("a").toString());
        assertThat(available("2027-10-10", "2027-10-12")).isEqualTo(1);
        assertThat(count("reservations")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action IN ('RESERVATION_CREATED','RESERVATION_CANCELLED')", Integer.class)).isEqualTo(2);
    }
    @Test void staffCreationSetsActorAndOnlyAcceptsActiveCustomers() throws Exception {
        var staff = login("employee"); var b = body(); b.put("customerId", users.get("a"));
        var response = staff.send("POST", "/staff/reservations", b, UUID.randomUUID());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(tree(response).path("customerId").asText()).isEqualTo(users.get("a").toString());
        assertThat(tree(response).path("createdBy").asText()).isEqualTo(users.get("employee").toString());
        for (var invalid : List.of(users.get("admin"), UUID.randomUUID())) {
            b.put("customerId", invalid);
            assertThat(staff.send("POST", "/staff/reservations", b, UUID.randomUUID()).statusCode()).isEqualTo(404);
        }
        jdbc.update("UPDATE users SET active=false WHERE id=?", users.get("b")); b.put("customerId", users.get("b"));
        assertThat(staff.send("POST", "/staff/reservations", b, UUID.randomUUID()).statusCode()).isEqualTo(404);
        var directory = tree(staff.get("/staff/customers?size=1"));
        assertThat(directory.path("totalElements").asInt()).isEqualTo(1);
        assertThat(directory.path("items").get(0).properties()).hasSize(3);
    }
    @Test void rejectsMassAssignmentRolesAndIdor() throws Exception {
        var a = login("a"); var b = login("b"); var employee = login("employee");
        for (String field : List.of("customerId", "createdBy", "status", "price", "nightlyRate", "total", "currency", "role")) {
            var request = body(); request.put(field, "ADMIN");
            assertThat(a.send("POST", "/reservations", request, UUID.randomUUID()).statusCode()).as(field).isEqualTo(400);
        }
        var staffBody = body(); staffBody.put("customerId", users.get("a"));
        assertThat(a.send("POST", "/staff/reservations", staffBody, UUID.randomUUID()).statusCode()).isEqualTo(403);
        assertThat(employee.send("POST", "/reservations", body(), UUID.randomUUID()).statusCode()).isEqualTo(403);
        assertThat(a.get("/staff/customers").statusCode()).isEqualTo(403);
        assertThat(a.get("/reservations?customerId=" + users.get("b")).statusCode()).isEqualTo(403);
        var r = create(a, body(), UUID.randomUUID());
        assertThat(b.get("/reservations/" + id(r)).statusCode()).isEqualTo(404);
        assertThat(b.send("POST", "/reservations/" + id(r) + "/cancel", Map.of("version", 0, "reason", "Test"), null).statusCode()).isEqualTo(404);
        assertThat(tree(b.get("/reservations")).path("totalElements").asInt()).isZero();
        assertThat(employee.get("/reservations/" + id(r)).statusCode()).isEqualTo(200);
        assertThat(guest().get("/reservations").statusCode()).isEqualTo(401);
        assertThat(guest().send("POST", "/reservations", body(), UUID.randomUUID()).statusCode()).isEqualTo(401);
    }
    @Test void preservesHistoricalPriceAndRejectsIncompatibleCatalogEdits() throws Exception {
        var a = login("a"); var admin = login("admin"); var r = create(a, body(), UUID.randomUUID());
        var rate = admin.send("PATCH", "/room-types/" + type, Map.of("version", 0, "basePrice", "500.00"), null);
        assertThat(rate.statusCode()).as(rate.body()).isEqualTo(200);
        assertThat(tree(a.get("/reservations/" + id(r))).path("totalAmount").decimalValue()).isEqualByComparingTo("251.00");
        for (var change : List.of(Map.of("version", 0, "operationalStatus", "MAINTENANCE"), Map.of("version", 0, "active", false)))
            assertThat(admin.send("PATCH", "/rooms/" + room, change, null).statusCode()).isEqualTo(409);
        assertThat(admin.send("PATCH", "/room-types/" + type, Map.of("version", 1, "capacity", 1), null).statusCode()).isEqualTo(409);
        assertThat(admin.send("PATCH", "/room-types/" + type, Map.of("version", 1, "active", false), null).statusCode()).isEqualTo(409);
    }
    @ParameterizedTest @ValueSource(strings = {"2027-10-10/2027-10-12", "2027-10-09/2027-10-11", "2027-10-11/2027-10-13", "2027-10-10/2027-10-11", "2027-10-09/2027-10-13"})
    void rejectsEveryOverlapShape(String interval) throws Exception {
        var a = login("a"); create(a, body(), UUID.randomUUID()); var dates = interval.split("/");
        var next = body(); next.put("checkIn", dates[0]); next.put("checkOut", dates[1]);
        var response = a.send("POST", "/reservations", next, UUID.randomUUID());
        assertThat(response.statusCode()).isEqualTo(409); assertThat(tree(response).path("code").asText()).isEqualTo("ROOM_NOT_AVAILABLE");
        assertThat(available(dates[0], dates[1])).isZero();
        assertThat(count("reservations")).isEqualTo(1); assertThat(count("idempotency_requests")).isEqualTo(1);
    }
    @Test void permitsAdjacentStaysAndDifferentRooms() throws Exception {
        var a = login("a"); create(a, body(), UUID.randomUUID());
        var adjacent = body(); adjacent.put("checkIn", "2027-10-12"); adjacent.put("checkOut", "2027-10-14");
        assertThat(available("2027-10-12", "2027-10-14")).isEqualTo(1); create(a, adjacent, UUID.randomUUID());
        var other = UUID.randomUUID();
        jdbc.update("INSERT INTO rooms(id,room_type_id,code,floor,operational_status,active,created_at,updated_at) VALUES(?,?,'102',1,'ACTIVE',true,now(),now())", other, type);
        var different = body(); different.put("roomId", other); create(a, different, UUID.randomUUID());
        assertThat(count("reservations")).isEqualTo(3);
    }
    @ParameterizedTest @ValueSource(strings = {"CONFIRMED", "CHECKED_IN", "CHECKED_OUT", "CANCELLED", "NO_SHOW"})
    void availabilityAndDatabaseAgreeOnBlockingStates(String status) throws Exception {
        var a = login("a"); var r = create(a, body(), UUID.randomUUID());
        jdbc.update("UPDATE reservations SET status=?,cancellation_reason='Fixture',cancelled_by=?,cancelled_at=now() WHERE id=?", status, users.get("a"), UUID.fromString(id(r)));
        boolean blocking = Set.of("CONFIRMED", "CHECKED_IN", "CHECKED_OUT").contains(status);
        assertThat(available("2027-10-10", "2027-10-12")).isEqualTo(blocking ? 0 : 1);
        assertThat(a.send("POST", "/reservations", body(), UUID.randomUUID()).statusCode()).isEqualTo(blocking ? 409 : 201);
    }
    @Test void validatesDatesGuestsKeysAndMissingRoomWithoutPartialRecords() throws Exception {
        var a = login("a");
        for (var invalid : List.of(Map.of("checkIn", "bad"), Map.of("checkOut", "2027-10-10"), Map.of("checkOut", "2027-10-09"), Map.of("guests", 0), Map.of("guests", -1), Map.of("guests", 1.5))) {
            var request = body(); request.putAll(invalid);
            assertThat(a.send("POST", "/reservations", request, UUID.randomUUID()).statusCode()).isEqualTo(400);
        }
        assertThat(a.send("POST", "/reservations", body(), null).statusCode()).isEqualTo(400);
        var missing = body(); missing.put("roomId", UUID.randomUUID());
        assertThat(a.send("POST", "/reservations", missing, UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(count("reservations")).isZero(); assertThat(count("idempotency_requests")).isZero();
    }
    @ParameterizedTest @ValueSource(strings = {"UPDATE rooms SET active=false", "UPDATE room_types SET active=false", "UPDATE room_types SET capacity=1", "UPDATE rooms SET operational_status='MAINTENANCE'", "UPDATE rooms SET operational_status='OUT_OF_SERVICE'"})
    void rejectsIneligibleCatalog(String sql) throws Exception {
        var a = login("a"); jdbc.update(sql);
        assertThat(a.send("POST", "/reservations", body(), UUID.randomUUID()).statusCode()).isEqualTo(409);
        assertThat(count("reservations")).isZero(); assertThat(count("idempotency_requests")).isZero();
    }
    @Test void idempotencyReplaysOriginalReceiptEvenAfterCancellationAndRejectsReuse() throws Exception {
        var a = login("a"); var key = UUID.randomUUID(); var first = create(a, body(), key);
        assertThat(create(a, body(), key)).isEqualTo(first);
        var different = body(); different.put("guests", 1);
        var conflict = a.send("POST", "/reservations", different, key);
        assertThat(conflict.statusCode()).isEqualTo(409); assertThat(tree(conflict).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        a.send("POST", "/reservations/" + id(first) + "/cancel", Map.of("version", 0, "reason", "Test"), null);
        assertThat(create(a, body(), key)).isEqualTo(first);
        assertThat(count("reservations")).isEqualTo(1); assertThat(count("idempotency_requests")).isEqualTo(1);
        jdbc.update("UPDATE idempotency_requests SET created_at='2026-01-01',expires_at='2026-01-02'");
        assertThat(tree(a.send("POST", "/reservations", body(), key)).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_EXPIRED");
    }
    @Test void concurrentSameKeyRequestsProduceOneLogicalOperation() throws Exception {
        var a = login("a"); var key = UUID.randomUUID();
        var responses = parallel(() -> a.send("POST", "/reservations", body(), key), () -> a.send("POST", "/reservations", body(), key));
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactly(201, 201);
        assertThat(tree(responses.get(0))).isEqualTo(tree(responses.get(1)));
        assertThat(count("reservations")).isEqualTo(1); assertThat(count("idempotency_requests")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='RESERVATION_CREATED'", Integer.class)).isEqualTo(1);
    }
    @Test void concurrentDifferentKeysReturnOneCreatedAndOneConflict() throws Exception {
        var a = login("a"); var b = login("b");
        var responses = parallel(() -> a.send("POST", "/reservations", body(), UUID.randomUUID()), () -> b.send("POST", "/reservations", body(), UUID.randomUUID()));
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201, 409);
        assertThat(tree(responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow()).path("code").asText()).isEqualTo("ROOM_NOT_AVAILABLE");
        assertThat(count("reservations")).isEqualTo(1); assertThat(count("idempotency_requests")).isEqualTo(1);
    }
    @Test void postgresExclusionRejectsARealCompetingInsertEvenWithoutApplicationLocks() throws Exception {
        try (var first = jdbc.getDataSource().getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            first.setAutoCommit(false); insert(first);
            var second = executor.submit(() -> {
                try (var connection = jdbc.getDataSource().getConnection()) {
                    connection.setAutoCommit(false);
                    connection.createStatement().execute("SET LOCAL application_name='phase5-overlap-contender'");
                    connection.createStatement().execute("SET LOCAL statement_timeout='15s'");
                    try { insert(connection); connection.commit(); return "COMMITTED"; }
                    catch (SQLException rejected) { connection.rollback(); return rejected.getSQLState(); }
                }
            });
            boolean blocked = false;
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline) {
                    blocked = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name='phase5-overlap-contender' AND wait_event_type='Lock')", Boolean.class));
                    if (blocked) break;
                    Thread.sleep(25);
                }
                assertThat(blocked).as("Second independent connection must wait on the uncommitted exclusion conflict").isTrue();
            } finally { first.commit(); }
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo("23P01");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations WHERE status='CONFIRMED'", Integer.class)).isEqualTo(1);
        }
    }
    @Test void cancellationUsesHotelCalendarReasonVersionOwnershipAndState() throws Exception {
        var a = login("a"); var staff = login("employee");
        var tomorrow = body(); tomorrow.put("checkIn", "2027-01-01"); tomorrow.put("checkOut", "2027-01-02");
        var r = create(a, tomorrow, UUID.randomUUID());
        assertThat(r.path("canCancel").asBoolean()).isTrue(); // Lima is still Dec 31, UTC already Jan 1.
        assertThat(a.send("POST", "/reservations/" + id(r) + "/cancel", Map.of("version", 0, "reason", " "), null).statusCode()).isEqualTo(400);
        assertThat(a.send("POST", "/reservations/" + id(r) + "/cancel", Map.of("version", 9, "reason", "Test"), null).statusCode()).isEqualTo(409);
        jdbc.update("UPDATE reservations SET check_in='2026-12-31',check_out='2027-01-01' WHERE id=?", UUID.fromString(id(r)));
        assertThat(a.send("POST", "/reservations/" + id(r) + "/cancel", Map.of("version", 0, "reason", "Test"), null).statusCode()).isEqualTo(400);
        var cancelled = staff.send("POST", "/reservations/" + id(r) + "/cancel", Map.of("version", 0, "reason", "Cancelación por personal"), null);
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(a.send("POST", "/reservations/" + id(r) + "/cancel", Map.of("version", 1, "reason", "Test"), null).statusCode()).isEqualTo(409);
    }
    @Test void concurrentCancellationPreservesOneTransitionAndOneAudit() throws Exception {
        var a = login("a"); var r = create(a, body(), UUID.randomUUID());
        Callable<HttpResponse<String>> cancel = () -> a.send("POST", "/reservations/" + id(r) + "/cancel", Map.of("version", 0, "reason", "Test"), null);
        var responses = parallel(cancel, cancel);
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='RESERVATION_CANCELLED'", Integer.class)).isEqualTo(1);
    }
    @Test void historyFiltersAndPagesAreAppliedOnTheServer() throws Exception {
        var a = login("a"); var admin = login("admin"); var first = create(a, body(), UUID.randomUUID());
        var next = body(); next.put("checkIn", "2027-11-10"); next.put("checkOut", "2027-11-12"); create(a, next, UUID.randomUUID());
        var page = tree(admin.get("/reservations?customerId=" + users.get("a") + "&roomId=" + room + "&size=1&sort=checkIn,desc"));
        assertThat(page.path("totalElements").asInt()).isEqualTo(2); assertThat(page.path("items")).hasSize(1);
        assertThat(tree(a.get("/reservations?code=" + first.path("code").asText())).path("totalElements").asInt()).isEqualTo(1);
        assertThat(tree(a.get("/reservations?checkInFrom=2027-10-10&checkInTo=2027-10-10&status=CONFIRMED")).path("totalElements").asInt()).isEqualTo(1);
        for (String query : List.of("size=101", "page=-1", "sort=customer.passwordHash,asc", "checkInFrom=2027-11-01&checkInTo=2027-10-01"))
            assertThat(a.get("/reservations?" + query).statusCode()).isEqualTo(400);
    }
    @Test void rollbackRemovesReservationReceiptAndAuditWhenAuditInsertFails() throws Exception {
        var a = login("a");
        jdbc.execute("CREATE FUNCTION reject_reservation_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='RESERVATION_CREATED' THEN RAISE EXCEPTION 'synthetic test failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_reservation_audit BEFORE INSERT ON audit_events FOR EACH ROW EXECUTE FUNCTION reject_reservation_audit()");
        try {
            assertThat(a.send("POST", "/reservations", body(), UUID.randomUUID()).statusCode()).isGreaterThanOrEqualTo(400);
            assertThat(count("reservations")).isZero(); assertThat(count("idempotency_requests")).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='RESERVATION_CREATED'", Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER reject_reservation_audit ON audit_events"); jdbc.execute("DROP FUNCTION reject_reservation_audit()");
        }
    }
    @Test void databaseConstraintsAndOpenApiCoverTheReservationContract() throws Exception {
        var a = login("a"); create(a, body(), UUID.randomUUID());
        for (String sql : List.of("UPDATE reservations SET guests=0", "UPDATE reservations SET check_out=check_in", "UPDATE reservations SET total_amount=1", "UPDATE reservations SET status='BAD'", "UPDATE reservations SET currency='pen'", "UPDATE reservations SET status='CANCELLED'", "UPDATE reservations SET customer_id='00000000-0000-0000-0000-000000000099'"))
            assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
        var definition = jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='ex_reservations_room_stay'", String.class);
        assertThat(definition).contains("EXCLUDE USING gist", "CHECKED_OUT", "daterange");
        var docs = json.readTree(a.http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v3/api-docs")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()).path("paths");
        for (String path : List.of("/api/v1/reservations", "/api/v1/staff/reservations", "/api/v1/reservations/{id}/cancel"))
            assertThat(docs.path(path).path("post").path("security").toString()).contains("bearerAuth");
        assertThat(docs.path("/api/v1/reservations").path("post").path("parameters").toString()).contains("Idempotency-Key", "required");
        assertThat(docs.path("/api/v1/reservations").path("post").path("responses").has("201")).isTrue();
        assertThat(docs.toString()).contains("/check-in", "/check-out", "/no-show");
    }
    @Test void migrationUpgradesExistingV3CatalogWithoutLosingData() throws Exception {
        try (var upgrade = new PostgreSQLContainer("postgres:18.6-alpine")) {
            upgrade.start();
            org.flywaydb.core.Flyway.configure().dataSource(upgrade.getJdbcUrl(), upgrade.getUsername(), upgrade.getPassword()).target("3").load().migrate();
            try (var connection = DriverManager.getConnection(upgrade.getJdbcUrl(), upgrade.getUsername(), upgrade.getPassword())) {
                connection.createStatement().execute("INSERT INTO room_types(id,name,description,capacity,base_price,created_at,updated_at) VALUES('00000000-0000-0000-0000-000000000005','Legacy','Keep',2,10,now(),now())");
            }
            var flyway = org.flywaydb.core.Flyway.configure().dataSource(upgrade.getJdbcUrl(), upgrade.getUsername(), upgrade.getPassword()).target("4").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1); flyway.validate();
            try (var connection = DriverManager.getConnection(upgrade.getJdbcUrl(), upgrade.getUsername(), upgrade.getPassword())) {
                var result = connection.createStatement().executeQuery("SELECT count(*) FROM room_types WHERE name='Legacy'"); result.next(); assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }
    private int available(String in, String out) throws Exception { return tree(guest().get("/rooms/availability?checkIn=" + in + "&checkOut=" + out + "&guests=2")).path("totalElements").asInt(); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private JsonNode tree(HttpResponse<String> response) { return json.readTree(response.body()); }
    private String id(JsonNode n) { return n.path("id").asText(); }
    private Map<String,Object> body() { return new HashMap<>(Map.of("roomId", room, "checkIn", "2027-10-10", "checkOut", "2027-10-12", "guests", 2)); }
    private JsonNode create(Client client, Object body, UUID key) throws Exception {
        var response = client.send("POST", "/reservations", body, key);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201); return tree(response);
    }
    private void insert(Connection connection) throws SQLException {
        var statement = connection.prepareStatement("INSERT INTO reservations(id,code,customer_id,created_by,room_id,check_in,check_out,guests,status,agreed_nightly_rate,total_amount,currency,created_at,updated_at) VALUES(?,?,?,?,?,'2027-10-10','2027-10-12',2,'CONFIRMED',125.50,251.00,'PEN',now(),now())");
        statement.setObject(1, UUID.randomUUID()); statement.setString(2, "R-" + UUID.randomUUID());
        statement.setObject(3, users.get("a")); statement.setObject(4, users.get("a")); statement.setObject(5, room);
        try (statement) { statement.executeUpdate(); }
    }
    private <T> List<T> parallel(Callable<T> a, Callable<T> b) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CyclicBarrier(2);
            var one = executor.submit(() -> { gate.await(10, TimeUnit.SECONDS); return a.call(); });
            var two = executor.submit(() -> { gate.await(10, TimeUnit.SECONDS); return b.call(); });
            return List.of(one.get(45, TimeUnit.SECONDS), two.get(45, TimeUnit.SECONDS));
        }
    }
    private Client guest() { var client = new Client(); clients.add(client); return client; }
    private Client login(String name) throws Exception {
        var c = guest(); var response = c.send("POST", "/auth/login", Map.of("email", name + "@example.test", "password", PASSWORD), null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200); c.access = tree(response).path("accessToken").asText(); return c;
    }
    private class Client {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(10)).build();
        String access;
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
        HttpResponse<String> send(String method, String path, Object body, UUID key) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(35)).header("Content-Type", "application/json");
            if (access != null) request.header("Authorization", "Bearer " + access);
            if (key != null) request.header("Idempotency-Key", key.toString());
            if (!method.equals("GET")) {
                request.header("Origin", "http://localhost:3000");
                if (access == null) {
                    var csrf = tree(get("/auth/csrf")); request.header(csrf.path("headerName").asText(), csrf.path("token").asText());
                }
            }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
