package com.portfolio.reservation;

import static org.assertj.core.api.Assertions.*;
import com.portfolio.reservation.payments.application.RefundService;
import com.portfolio.reservation.payments.domain.*;
import com.portfolio.reservation.payments.infrastructure.DeterministicPaymentSimulator;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
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

@Testcontainers @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PaymentIT.SimulationConfiguration.class)
class PaymentIT {
    @Container static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18.6-alpine");
    @TestConfiguration static class SimulationConfiguration {
        @Bean @Primary ControlledSimulator controlledSimulator() { return new ControlledSimulator(); }
    }
    static class ControlledSimulator implements PaymentSimulator {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean declineAll;
        @Override public Payment.Result evaluate(long previousAttempts) {
            calls.incrementAndGet();
            return declineAll ? Payment.Result.DECLINED : new DeterministicPaymentSimulator().evaluate(previousAttempts);
        }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", DATABASE::getJdbcUrl); p.add("spring.datasource.username", DATABASE::getUsername);
        p.add("spring.datasource.password", DATABASE::getPassword);
        p.add("security.auth.jwt-secret", () -> Base64.getEncoder().encodeToString(new byte[32]));
        p.add("security.auth.cookie-secure", () -> false);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;
    @Autowired ControlledSimulator simulator;
    @Autowired RefundService settlement;
    // Synthetic fixture credentials, usable only in this disposable PostgreSQL database.
    static final String PASSWORD = "Synthetic payment fixture 123!";
    static String passwordHash;
    UUID reservation, room, type;
    Map<String, UUID> users;
    final List<Client> clients = new ArrayList<>();
    @BeforeEach void prepare() {
        jdbc.execute("TRUNCATE idempotency_requests,refunds,payments,reservations,rooms,room_types,auth_rate_limits,audit_events,refresh_tokens,refresh_sessions,users");
        simulator.calls.set(0); simulator.declineAll = false;
        if (passwordHash == null) passwordHash = encoder.encode(PASSWORD);
        users = new HashMap<>();
        for (String name : List.of("a", "b", "employee", "admin")) {
            var id = UUID.randomUUID(); users.put(name, id);
            String role = name.equals("employee") ? "EMPLEADO" : name.equals("admin") ? "ADMIN" : "CLIENTE";
            jdbc.update("INSERT INTO users(id,role_id,name,email,password_hash,created_at,updated_at) SELECT ?,id,?,?,?,now(),now() FROM roles WHERE name=?", id, name, name + "@example.test", passwordHash, role);
        }
        type = UUID.randomUUID(); room = UUID.randomUUID(); reservation = UUID.randomUUID();
        jdbc.update("INSERT INTO room_types(id,name,description,capacity,base_price,created_at,updated_at) VALUES(?,'Synthetic Suite','Test',2,125.50,now(),now())", type);
        jdbc.update("INSERT INTO rooms(id,room_type_id,code,floor,operational_status,created_at,updated_at) VALUES(?,?,'101',1,'ACTIVE',now(),now())", room, type);
        seedReservation(reservation, "2030-10-10", "2030-10-12");
    }
    @AfterEach void closeClients() { clients.forEach(c -> c.http.close()); clients.clear(); }

    @Test void deterministicSimulatorAndHistoricalFullAmountComeFromTheServer() throws Exception {
        var a = login("a");
        jdbc.update("UPDATE reservations SET currency='USD' WHERE id=?", reservation);
        jdbc.update("UPDATE room_types SET base_price=999 WHERE id=?", type);
        var declined = pay(a, reservation, key()); assertResult(declined, "DECLINED");
        var approved = pay(a, reservation, key()); assertResult(approved, "APPROVED");
        for (var attempt : List.of(declined, approved)) {
            assertThat(attempt.path("amount").decimalValue()).isEqualByComparingTo("251.00");
            assertThat(attempt.path("currency").asText()).isEqualTo("USD");
            assertThat(attempt.path("actorId").asText()).isEqualTo(users.get("a").toString());
            assertThat(attempt.path("simulatedReference").asText()).startsWith("SIM-P-");
        }
        assertThat(declined.path("simulatedReference")).isNotEqualTo(approved.path("simulatedReference"));
        assertThat(settlement.isFullyPaid(reservation)).isTrue();
        var history = history(a, reservation);
        assertThat(history.path("settlement").asText()).isEqualTo("PAID");
        assertThat(history.path("amountDue").decimalValue()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT status FROM reservations WHERE id=?", String.class, reservation)).isEqualTo("CONFIRMED");
    }
    @ParameterizedTest @ValueSource(strings = {"a", "employee", "admin"})
    void ownerAndBothStaffRolesCanPayAndRead(String role) throws Exception {
        var client = login(role); var payment = pay(client, reservation, key());
        assertThat(payment.path("actorId").asText()).isEqualTo(users.get(role).toString());
        assertThat(history(client, reservation).path("attempts").path("totalElements").asInt()).isEqualTo(1);
    }
    @Test void ownershipAuthenticationAndMassAssignmentRemainProtected() throws Exception {
        var a = login("a"); var b = login("b");
        assertThat(b.send("POST", path(reservation), Map.of(), key()).statusCode()).isEqualTo(404);
        assertThat(b.get(path(reservation)).statusCode()).isEqualTo(404);
        assertThat(a.get(path(UUID.randomUUID())).statusCode()).isEqualTo(404);
        assertThat(guest().get(path(reservation)).statusCode()).isEqualTo(401);
        assertThat(guest().send("POST", path(reservation), Map.of(), key()).statusCode()).isEqualTo(401);
        for (String field : List.of("amount", "currency", "result", "status", "simulatedReference", "actorId", "createdBy", "customerId", "refund", "scenario"))
            assertThat(a.send("POST", path(reservation), Map.of(field, "forged"), key()).statusCode()).as(field).isEqualTo(400);
        assertThat(a.send("POST", path(reservation), Map.of(), null).statusCode()).isEqualTo(400);
        assertThat(a.send("POST", path(reservation), Map.of(), "not-a-uuid").statusCode()).isEqualTo(400);
        assertThat(a.send("POST", path(reservation), null, key()).statusCode()).isEqualTo(400);
        assertThat(count("payments")).isZero(); assertThat(count("idempotency_requests")).isZero();
    }
    @ParameterizedTest @ValueSource(strings = {"CANCELLED", "CHECKED_IN", "CHECKED_OUT", "NO_SHOW"})
    void onlyConfirmedReservationsArePayable(String status) throws Exception {
        var a = login("a");
        jdbc.update("UPDATE reservations SET status=?,cancellation_reason='Fixture',cancelled_by=?,cancelled_at=now() WHERE id=?", status, users.get("a"), reservation);
        var response = a.send("POST", path(reservation), Map.of(), key());
        assertThat(response.statusCode()).isEqualTo(409); assertThat(tree(response).path("code").asText()).isEqualTo("RESERVATION_NOT_PAYABLE");
        assertThat(history(a, reservation).path("canPay").asBoolean()).isFalse();
        assertThat(count("payments")).isZero(); assertThat(count("idempotency_requests")).isZero();
    }
    @Test void preservesMultipleDeclinesAndProvidesStablePaginatedHistory() throws Exception {
        var a = login("a"); simulator.declineAll = true;
        assertResult(pay(a, reservation, key()), "DECLINED"); assertResult(pay(a, reservation, key()), "DECLINED");
        simulator.declineAll = false; assertResult(pay(a, reservation, key()), "APPROVED");
        var page = tree(a.get(path(reservation) + "?size=1&page=1&sort=createdAt,asc"));
        assertThat(page.path("attempts").path("totalElements").asInt()).isEqualTo(3);
        assertThat(page.path("attempts").path("items")).hasSize(1);
        for (String query : List.of("size=101", "page=-1", "sort=amount,desc")) assertThat(a.get(path(reservation) + "?" + query).statusCode()).isEqualTo(400);
        assertThat(a.send("POST", path(reservation), Map.of(), key()).statusCode()).isEqualTo(409);
        assertThat(count("payments")).isEqualTo(3);
    }
    @Test void replaysDeclinedApprovedAndPreRefundSnapshotsWithoutExecutingAgain() throws Exception {
        var a = login("a"); var firstKey = key(); var secondKey = key();
        var declined = pay(a, reservation, firstKey);
        assertThat(pay(a, reservation, firstKey)).isEqualTo(declined);
        assertThat(simulator.calls.get()).isEqualTo(1);
        var approved = pay(a, reservation, secondKey);
        assertThat(pay(a, reservation, secondKey)).isEqualTo(approved);
        assertThat(simulator.calls.get()).isEqualTo(2);
        assertThat(cancel(a).statusCode()).isEqualTo(200);
        assertThat(pay(a, reservation, secondKey)).isEqualTo(approved);
        assertThat(pay(a, reservation, firstKey)).isEqualTo(declined);
        assertThat(simulator.calls.get()).isEqualTo(2);
        assertThat(count("payments")).isEqualTo(2); assertThat(count("idempotency_requests")).isEqualTo(2);
        assertThat(audit("PAYMENT_ATTEMPTED")).isEqualTo(2); assertThat(audit("PAYMENT_DECLINED")).isEqualTo(1); assertThat(audit("PAYMENT_APPROVED")).isEqualTo(1);
        UUID other = UUID.randomUUID(); seedReservation(other, "2030-11-10", "2030-11-12");
        var reused = a.send("POST", path(other), Map.of(), secondKey);
        assertThat(reused.statusCode()).isEqualTo(409); assertThat(tree(reused).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        jdbc.update("UPDATE idempotency_requests SET created_at='2020-01-01',expires_at='2020-01-02' WHERE request_key=?", UUID.fromString(firstKey));
        assertThat(tree(a.send("POST", path(reservation), Map.of(), firstKey)).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_EXPIRED");
    }
    @Test void concurrentSameKeyProducesExactlyOneAttemptAndAuditPair() throws Exception {
        var a = login("a"); var key = key();
        var responses = parallel(() -> a.send("POST", path(reservation), Map.of(), key), () -> a.send("POST", path(reservation), Map.of(), key));
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactly(201, 201);
        assertThat(tree(responses.get(0))).isEqualTo(tree(responses.get(1)));
        assertThat(count("payments")).isEqualTo(1); assertThat(count("idempotency_requests")).isEqualTo(1);
        assertThat(simulator.calls.get()).isEqualTo(1); assertThat(audit("PAYMENT_ATTEMPTED")).isEqualTo(1);
    }
    @Test void concurrentNewPaymentIntentsProduceOnlyOneApprovedPayment() throws Exception {
        var a = login("a"); var staff = login("employee"); pay(a, reservation, key());
        var responses = parallel(() -> a.send("POST", path(reservation), Map.of(), key()), () -> staff.send("POST", path(reservation), Map.of(), key()));
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201, 409);
        assertThat(approvedCount()).isEqualTo(1); assertThat(count("payments")).isEqualTo(2);
        assertThat(audit("PAYMENT_APPROVED")).isEqualTo(1);
    }
    @Test void postgresUniqueIndexRejectsACompetingApprovedInsertWithoutServiceLocks() throws Exception {
        assertSqlRace(() -> paymentSql("APPROVED"));
        assertThat(approvedCount()).isEqualTo(1);
    }
    @Test void paidCancellationCreatesOneFullHistoricalRefundWithActorAndReason() throws Exception {
        var a = login("a"); var staff = login("employee"); pay(a, reservation, key()); var approved = pay(a, reservation, key());
        jdbc.update("UPDATE room_types SET base_price=999 WHERE id=?", type);
        assertThat(cancel(staff).statusCode()).isEqualTo(200);
        var history = history(a, reservation); assertThat(history.path("settlement").asText()).isEqualTo("REFUNDED");
        var refund = jdbc.queryForMap("SELECT * FROM refunds");
        assertThat(refund.get("payment_id").toString()).isEqualTo(approved.path("id").asText());
        assertThat(refund.get("amount").toString()).isEqualTo("251.00"); assertThat(refund.get("currency")).isEqualTo("PEN");
        assertThat(refund.get("actor_id")).isEqualTo(users.get("employee")); assertThat(refund.get("reason")).isEqualTo("Synthetic cancellation");
        assertThat(settlement.isFullyPaid(reservation)).isFalse(); assertThat(audit("REFUND_CREATED")).isEqualTo(1);
        assertThat(cancel(staff).statusCode()).isEqualTo(409); assertThat(count("refunds")).isEqualTo(1);
    }
    @Test void cancellationWithoutApprovalCreatesNoRefund() throws Exception {
        var a = login("a"); pay(a, reservation, key());
        assertThat(cancel(a).statusCode()).isEqualTo(200); assertThat(count("refunds")).isZero();
        assertThat(settlement.isFullyPaid(reservation)).isFalse(); assertThat(count("payments")).isEqualTo(1);
    }
    @Test void concurrentPaidCancellationCreatesExactlyOneRefund() throws Exception {
        var a = login("a"); pay(a, reservation, key()); pay(a, reservation, key());
        var responses = parallel(() -> cancel(a), () -> cancel(a));
        assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200, 409);
        assertThat(count("refunds")).isEqualTo(1); assertThat(audit("REFUND_CREATED")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void paymentAndCancellationCompeteOnTheSameReservationInBothOrders(boolean paymentFirst) throws Exception {
        var a = login("a"); var staff = login("employee"); pay(a, reservation, key());
        String action = paymentFirst ? "PAYMENT_APPROVED" : "RESERVATION_CANCELLED";
        // Pause the winner inside its real HTTP transaction while it owns the Reservation lock.
        jdbc.execute("CREATE FUNCTION pause_payment_race() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='" + action + "' THEN PERFORM pg_advisory_xact_lock(606060); END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER pause_payment_race BEFORE INSERT ON audit_events FOR EACH ROW EXECUTE FUNCTION pause_payment_race()");
        try (var gate = jdbc.getDataSource().getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            gate.createStatement().execute("SELECT pg_advisory_lock(606060)");
            Future<HttpResponse<String>> first = executor.submit(() -> paymentFirst ? a.send("POST", path(reservation), Map.of(), key()) : cancel(staff));
            Future<HttpResponse<String>> second = null;
            try {
                awaitCondition(() -> jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND lower(wait_event)='advisory'", Integer.class) == 1);
                second = executor.submit(() -> paymentFirst ? cancel(staff) : a.send("POST", path(reservation), Map.of(), key()));
                awaitCondition(() -> jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock'", Integer.class) >= 2);
            } finally { gate.createStatement().execute("SELECT pg_advisory_unlock(606060)"); }
            assertThat(first.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(paymentFirst ? 201 : 200);
            assertThat(second.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(paymentFirst ? 200 : 409);
            assertThat(jdbc.queryForObject("SELECT status FROM reservations WHERE id=?", String.class, reservation)).isEqualTo("CANCELLED");
            assertThat(approvedCount()).isEqualTo(paymentFirst ? 1 : 0);
            assertThat(count("refunds")).isEqualTo(paymentFirst ? 1 : 0);
        } finally { jdbc.execute("DROP TRIGGER pause_payment_race ON audit_events"); jdbc.execute("DROP FUNCTION pause_payment_race()"); }
    }
    @Test void postgresUniquePaymentIdRejectsACompetingRefundInsert() throws Exception {
        var a = login("a"); pay(a, reservation, key()); var approved = pay(a, reservation, key());
        assertSqlRace(() -> "INSERT INTO refunds(id,payment_id,amount,currency,reason,actor_id,created_at) VALUES('" + UUID.randomUUID() + "','" + approved.path("id").asText() + "',251.00,'PEN','Synthetic SQL race','" + users.get("a") + "',now())");
        assertThat(count("refunds")).isEqualTo(1);
    }
    @Test void paymentAuditFailureRollsBackAttemptAndReceipt() throws Exception {
        var a = login("a"); pay(a, reservation, key()); installAuditFailure("PAYMENT_APPROVED");
        try {
            assertThat(a.send("POST", path(reservation), Map.of(), key()).statusCode()).isEqualTo(500);
            assertThat(approvedCount()).isZero(); assertThat(count("payments")).isEqualTo(1);
            assertThat(count("idempotency_requests")).isEqualTo(1); assertThat(audit("PAYMENT_ATTEMPTED")).isEqualTo(1);
        } finally { removeAuditFailure(); }
    }
    @Test void refundFailureRollsBackCancellationAndPreservesApprovedPayment() throws Exception {
        var a = login("a"); pay(a, reservation, key()); pay(a, reservation, key()); installAuditFailure("REFUND_CREATED");
        try {
            assertThat(cancel(a).statusCode()).isEqualTo(500);
            assertThat(jdbc.queryForObject("SELECT status FROM reservations WHERE id=?", String.class, reservation)).isEqualTo("CONFIRMED");
            assertThat(count("refunds")).isZero(); assertThat(approvedCount()).isEqualTo(1); assertThat(audit("RESERVATION_CANCELLED")).isZero();
        } finally { removeAuditFailure(); }
    }
    @Test void databaseChecksAndRefundDomainRejectInvalidData() throws Exception {
        var a = login("a"); pay(a, reservation, key()); pay(a, reservation, key());
        for (String sql : List.of("UPDATE payments SET amount=0", "UPDATE payments SET currency='pen'", "UPDATE payments SET result='PENDING'", "UPDATE payments SET reservation_id='00000000-0000-0000-0000-000000000099'", "UPDATE payments SET simulated_reference='duplicate'"))
            assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> new Refund(new Payment(reservation, new java.math.BigDecimal("251"), "PEN", Payment.Result.DECLINED, users.get("a"), Instant.now()), "Test", users.get("a"), Instant.now())).isInstanceOf(IllegalArgumentException.class);
        cancel(a);
        for (String sql : List.of("UPDATE refunds SET amount=0", "UPDATE refunds SET currency='pen'", "UPDATE refunds SET reason=' '", "UPDATE refunds SET payment_id='00000000-0000-0000-0000-000000000099'"))
            assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void openApiDocumentsPaymentPolicyAndNoManualRefundRoute() throws Exception {
        var c = guest(); var docs = json.readTree(c.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
        var route = docs.path("paths").path("/api/v1/reservations/{id}/payments");
        for (String verb : List.of("get", "post")) {
            assertThat(route.path(verb).path("security").toString()).contains("bearerAuth");
            for (String code : List.of("400", "401", "403", "404", "409")) assertThat(route.path(verb).path("responses").has(code)).isTrue();
        }
        assertThat(route.path("post").path("responses").has("201")).isTrue();
        assertThat(route.path("get").path("responses").has("200")).isTrue();
        assertThat(route.path("post").path("parameters").toString()).contains("Idempotency-Key", "required");
        assertThat(docs.path("paths").toString()).doesNotContain("/refund").contains("/check-in", "/check-out", "/no-show");
    }
    @Test void upgradeFromV4PreservesReservationAndExistingReceipt() throws Exception {
        try (var upgrade = new PostgreSQLContainer("postgres:18.6-alpine")) {
            upgrade.start();
            org.flywaydb.core.Flyway.configure().dataSource(upgrade.getJdbcUrl(), upgrade.getUsername(), upgrade.getPassword()).target("4").load().migrate();
            try (var c = DriverManager.getConnection(upgrade.getJdbcUrl(), upgrade.getUsername(), upgrade.getPassword())) {
                var s = c.createStatement();
                s.execute("INSERT INTO users(id,role_id,name,email,password_hash,created_at,updated_at) VALUES('" + users.get("a") + "','00000000-0000-0000-0000-000000000001','Legacy','legacy@example.test','synthetic-unused-hash',now(),now())");
                s.execute("INSERT INTO room_types(id,name,description,capacity,base_price,created_at,updated_at) VALUES('" + type + "','Legacy','Keep',2,125.50,now(),now())");
                s.execute("INSERT INTO rooms(id,room_type_id,code,floor,operational_status,created_at,updated_at) VALUES('" + room + "','" + type + "','LEGACY',1,'ACTIVE',now(),now())");
                s.execute(reservationSql(reservation, "2030-10-10", "2030-10-12"));
                s.execute("INSERT INTO idempotency_requests(id,actor_id,operation,request_key,request_hash,reservation_id,response_json,created_at,expires_at) VALUES('" + UUID.randomUUID() + "','" + users.get("a") + "','CREATE_RESERVATION','" + UUID.randomUUID() + "','" + "a".repeat(64) + "','" + reservation + "','{\"legacy\":true}',now(),now()+interval '24 hours')");
            }
            var flyway = org.flywaydb.core.Flyway.configure().dataSource(upgrade.getJdbcUrl(), upgrade.getUsername(), upgrade.getPassword()).target("5").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1); flyway.validate();
            try (var c = DriverManager.getConnection(upgrade.getJdbcUrl(), upgrade.getUsername(), upgrade.getPassword())) {
                var rs = c.createStatement().executeQuery("SELECT r.total_amount,i.response_json,i.payment_id FROM reservations r JOIN idempotency_requests i ON i.reservation_id=r.id");
                assertThat(rs.next()).isTrue(); assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("251.00");
                assertThat(rs.getString(2)).isEqualTo("{\"legacy\":true}"); assertThat(rs.getObject(3)).isNull();
            }
        }
    }
    private void seedReservation(UUID id, String in, String out) { jdbc.execute(reservationSql(id, in, out)); }
    private String reservationSql(UUID id, String in, String out) {
        return "INSERT INTO reservations(id,code,customer_id,created_by,room_id,check_in,check_out,guests,status,agreed_nightly_rate,total_amount,currency,created_at,updated_at) VALUES('" + id + "','R-" + id + "','" + users.get("a") + "','" + users.get("a") + "','" + room + "','" + in + "','" + out + "',2,'CONFIRMED',125.50,251.00,'PEN',now(),now())";
    }
    private String paymentSql(String result) { return "INSERT INTO payments(id,reservation_id,amount,currency,result,simulated_reference,actor_id,created_at) VALUES('" + UUID.randomUUID() + "','" + reservation + "',251.00,'PEN','" + result + "','SIM-P-" + UUID.randomUUID() + "','" + users.get("a") + "',now())"; }
    private void assertSqlRace(Supplier<String> insert) throws Exception {
        try (var first = jdbc.getDataSource().getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            first.setAutoCommit(false); first.createStatement().execute(insert.get());
            var second = executor.submit(() -> {
                try (var c = jdbc.getDataSource().getConnection()) {
                    c.setAutoCommit(false); c.createStatement().execute("SET LOCAL application_name='phase6-unique-contender'");
                    c.createStatement().execute("SET LOCAL statement_timeout='20s'");
                    try { c.createStatement().execute(insert.get()); c.commit(); return "COMMITTED"; }
                    catch (SQLException rejected) { c.rollback(); return rejected.getSQLState(); }
                }
            });
            try { awaitCondition(() -> Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name='phase6-unique-contender' AND wait_event_type='Lock')", Boolean.class))); }
            finally { first.commit(); }
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo("23505");
        }
    }
    private void awaitCondition(Supplier<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) { if (condition.get()) return; Thread.sleep(25); }
        fail("Expected a real concurrent PostgreSQL lock wait");
    }
    private void installAuditFailure(String action) {
        jdbc.execute("CREATE FUNCTION fail_payment_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='" + action + "' THEN RAISE EXCEPTION 'synthetic rollback test'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_payment_audit BEFORE INSERT ON audit_events FOR EACH ROW EXECUTE FUNCTION fail_payment_audit()");
    }
    private void removeAuditFailure() { jdbc.execute("DROP TRIGGER fail_payment_audit ON audit_events"); jdbc.execute("DROP FUNCTION fail_payment_audit()"); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private int approvedCount() { return jdbc.queryForObject("SELECT count(*) FROM payments WHERE result='APPROVED'", Integer.class); }
    private int audit(String action) { return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action=?", Integer.class, action); }
    private static String path(UUID id) { return "/reservations/" + id + "/payments"; }
    private static String key() { return UUID.randomUUID().toString(); }
    private JsonNode tree(HttpResponse<String> r) { return json.readTree(r.body()); }
    private void assertResult(JsonNode p, String result) { assertThat(p.path("result").asText()).isEqualTo(result); }
    private JsonNode pay(Client c, UUID id, String key) throws Exception {
        var response = c.send("POST", path(id), Map.of(), key); assertThat(response.statusCode()).as(response.body()).isEqualTo(201); return tree(response);
    }
    private JsonNode history(Client c, UUID id) throws Exception { var r = c.get(path(id)); assertThat(r.statusCode()).as(r.body()).isEqualTo(200); return tree(r); }
    private HttpResponse<String> cancel(Client c) throws Exception { return c.send("POST", "/reservations/" + reservation + "/cancel", Map.of("version", 0, "reason", "Synthetic cancellation"), null); }
    private Client guest() { var c = new Client(); clients.add(c); return c; }
    private Client login(String name) throws Exception {
        var c = guest(); var response = c.send("POST", "/auth/login", Map.of("email", name + "@example.test", "password", PASSWORD), null);
        assertThat(response.statusCode()).isEqualTo(200); c.access = tree(response).path("accessToken").asText(); return c;
    }
    private <T> List<T> parallel(Callable<T> a, Callable<T> b) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CyclicBarrier(2);
            var one = executor.submit(() -> { gate.await(10, TimeUnit.SECONDS); return a.call(); });
            var two = executor.submit(() -> { gate.await(10, TimeUnit.SECONDS); return b.call(); });
            return List.of(one.get(45, TimeUnit.SECONDS), two.get(45, TimeUnit.SECONDS));
        }
    }
    private class Client {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(10)).build();
        String access;
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
