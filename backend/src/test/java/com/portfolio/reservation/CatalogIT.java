package com.portfolio.reservation;

import static org.assertj.core.api.Assertions.*;
import com.portfolio.reservation.rooms.domain.Room;
import com.portfolio.reservation.rooms.infrastructure.RoomRepository;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogIT {
    @Container static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18.6-alpine");
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
    @Autowired RoomRepository rooms;
    @Autowired PlatformTransactionManager transactions;
    private static String hash;
    private static final String PASSWORD = "Catalog integration test only 123!";
    private final List<Client> clients = new ArrayList<>();

    @BeforeEach void prepare() {
        jdbc.execute("TRUNCATE idempotency_requests, refunds, payments, reservations, rooms, room_types, auth_rate_limits, audit_events, refresh_tokens, refresh_sessions, users");
        if (hash == null) hash = encoder.encode(PASSWORD);
        for (String role : List.of("ADMIN", "EMPLEADO", "CLIENTE"))
            jdbc.update("""
                    INSERT INTO users (id,role_id,name,email,password_hash,active,security_version,version,created_at,updated_at)
                    SELECT ?,id,?,?,?,true,0,0,now(),now() FROM roles WHERE name=?
                    """, UUID.randomUUID(), role, role.toLowerCase(Locale.ROOT)+"@example.test", hash, role);
    }
    @AfterEach void close() { clients.forEach(c -> c.http.close()); clients.clear(); }

    @Test void createsTypesWithExactPositivePriceAndSanitizedPublicDto() throws Exception {
        var admin = client("ADMIN");
        var created = createType(admin, "Suite", "125.50", 3);
        assertThat(created.get("basePrice").decimalValue()).isEqualByComparingTo("125.50");
        assertThat(created.get("version").asLong()).isZero();
        var response = guest().get("/room-types/"+id(created));
        assertThat(response.statusCode()).isEqualTo(200);
        var view = tree(response);
        assertThat(view.get("capacity").asInt()).isEqualTo(3);
        assertThat(view.get("currency").asText()).isEqualTo("PEN");
        assertThat(view.has("version")).isFalse();
        assertThat(view.has("active")).isFalse();
        assertThat(view.has("createdAt")).isFalse();
    }
    @Test void validatesTypeFieldsAndRejectsMassAssignment() throws Exception {
        var admin = client("ADMIN");
        for (var invalid : List.of(Map.of("name", ""), Map.of("capacity", 0), Map.of("capacity", 1001),
                Map.of("basePrice", "0"), Map.of("basePrice", "-1"), Map.of("basePrice", "1.001"),
                Map.of("basePrice", "10000000000"), Map.of("role", "ADMIN"), Map.of("id", UUID.randomUUID().toString()))) {
            var body = typeBody("Type", "10.00", 2); body.putAll(invalid);
            assertThat(admin.send("POST", "/room-types", body).statusCode()).as(invalid.toString()).isEqualTo(400);
        }
    }
    @Test void rejectsDuplicateTypeNamesCaseInsensitivelyAndRollsBackAudit() throws Exception {
        var admin = client("ADMIN");
        createType(admin, " Suite ", "50", 2);
        var response = admin.send("POST", "/room-types", typeBody("suite", "10", 1));
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(tree(response).get("code").asText()).isEqualTo("TYPE_NAME_TAKEN");
        assertThat(count("room_types")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='ROOM_TYPE_CREATED'", Integer.class)).isEqualTo(1);
    }
    @Test void patchesAndReactivatesTypesWithoutExposingInactiveRecords() throws Exception {
        var admin = client("ADMIN");
        var type = createType(admin, "Doble", "50", 2);
        var edited = admin.send("PATCH", "/room-types/"+id(type), Map.of("version", 0, "name", "Doble superior", "basePrice", "75.25", "active", false));
        assertThat(edited.statusCode()).isEqualTo(200);
        assertThat(tree(edited).get("capacity").asInt()).isEqualTo(2);
        assertThat(guest().get("/room-types/"+id(type)).statusCode()).isEqualTo(404);
        assertThat(items(guest().get("/room-types?active=false"))).isEmpty();
        assertThat(items(admin.get("/staff/room-types?active=false"))).hasSize(1);
        var enabled = admin.send("PATCH", "/room-types/"+id(type), Map.of("version", 1, "active", true));
        assertThat(enabled.statusCode()).isEqualTo(200);
        assertThat(guest().get("/room-types/"+id(type)).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT action FROM audit_events", String.class))
                .contains("ROOM_TYPE_CREATED", "ROOM_TYPE_DEACTIVATED", "ROOM_TYPE_ACTIVATED");
    }
    @Test void createsAndEditsRoomsIncludingTypeFloorCodeAndActivity() throws Exception {
        var admin = client("ADMIN");
        var first = createType(admin, "Doble", "50", 2);
        var second = createType(admin, "Suite", "90", 4);
        var room = createRoom(admin, first, " a-101 ", 1, "ACTIVE", true);
        assertThat(room.get("code").asText()).isEqualTo("A-101");
        var edited = admin.send("PATCH", "/rooms/"+id(room), Map.of("version", 0, "roomTypeId", id(second), "floor", 2, "code", "B-201", "active", false));
        assertThat(edited.statusCode()).isEqualTo(200);
        assertThat(tree(edited).get("roomType").get("id").asText()).isEqualTo(id(second));
        assertThat(tree(edited).get("floor").asInt()).isEqualTo(2);
        assertThat(guest().get("/rooms/"+id(room)).statusCode()).isEqualTo(404);
        assertThat(admin.send("PATCH", "/rooms/"+id(room), Map.of("version", 1, "active", true)).statusCode()).isEqualTo(200);
        var publicView = tree(guest().get("/rooms/"+id(room)));
        assertThat(publicView.has("version")).isFalse();
        assertThat(publicView.has("operationalStatus")).isFalse();
        assertThat(publicView.get("roomType").has("version")).isFalse();
    }
    @Test void validatesRoomFieldsAndMissingType() throws Exception {
        var admin = client("ADMIN");
        var type = createType(admin, "Doble", "50", 2);
        for (var invalid : List.of(Map.of("code", ""), Map.of("floor", -6), Map.of("floor", 201),
                Map.of("operationalStatus", "AVAILABLE"), Map.of("version", 3))) {
            var body = roomBody(type, "101", 1, "ACTIVE", true); body.putAll(invalid);
            assertThat(admin.send("POST", "/rooms", body).statusCode()).isEqualTo(400);
        }
        var body = roomBody(type, "101", 1, "ACTIVE", true); body.put("roomTypeId", UUID.randomUUID().toString());
        assertThat(admin.send("POST", "/rooms", body).statusCode()).isEqualTo(404);
    }
    @Test void rejectsDuplicateRoomCodes() throws Exception {
        var admin = client("ADMIN");
        var type = createType(admin, "Doble", "50", 2);
        createRoom(admin, type, "a101", 1, "ACTIVE", true);
        var response = admin.send("POST", "/rooms", roomBody(type, "A101", 1, "ACTIVE", true));
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(tree(response).get("code").asText()).isEqualTo("ROOM_CODE_TAKEN");
        assertThat(count("rooms")).isEqualTo(1);
    }
    @Test void publicCatalogAlwaysEnforcesVisibilityDespiteFilters() throws Exception {
        var admin = client("ADMIN");
        var type = createType(admin, "Doble", "50", 2);
        createRoom(admin, type, "101", 1, "ACTIVE", true);
        var hidden = createRoom(admin, type, "102", 1, "MAINTENANCE", true);
        createRoom(admin, type, "103", 1, "OUT_OF_SERVICE", true);
        createRoom(admin, type, "104", 1, "ACTIVE", false);
        assertThat(items(guest().get("/rooms"))).hasSize(1);
        assertThat(items(guest().get("/rooms?active=false"))).isEmpty();
        assertThat(items(guest().get("/rooms?operationalStatus=MAINTENANCE"))).isEmpty();
        assertThat(guest().get("/rooms/"+id(hidden)).statusCode()).isEqualTo(404);
        assertThat(items(admin.get("/staff/rooms"))).hasSize(4);
        admin.send("PATCH", "/room-types/"+id(type), Map.of("version", 0, "active", false));
        assertThat(items(guest().get("/rooms"))).isEmpty();
    }
    @Test void filtersAndPaginatesTypesAndRoomsWithStableAllowedSorting() throws Exception {
        var admin = client("ADMIN");
        var a = createType(admin, "Individual", "50", 1);
        var b = createType(admin, "Doble", "75", 2);
        createType(admin, "Suite", "100", 4);
        assertThat(items(guest().get("/room-types?minCapacity=2&maxCapacity=3&q=dob"))).hasSize(1);
        var first = tree(guest().get("/room-types?size=1&sort=basePrice,desc"));
        assertThat(first.get("totalElements").asInt()).isEqualTo(3);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
        assertThat(first.get("items").get(0).get("name").asText()).isEqualTo("Suite");
        assertThat(items(guest().get("/room-types?page=3&size=1"))).isEmpty();
        createRoom(admin, a, "101", 1, "ACTIVE", true);
        createRoom(admin, b, "102", 1, "ACTIVE", true);
        createRoom(admin, b, "201", 2, "MAINTENANCE", true);
        assertThat(items(admin.get("/staff/rooms?roomTypeId="+id(b)+"&floor=2&operationalStatus=MAINTENANCE&active=true&q=20"))).hasSize(1);
        assertThat(items(guest().get("/rooms?floor=1&size=1&page=1&sort=code,asc"))).hasSize(1);
    }
    @Test void rejectsInvalidFiltersAndPagination() throws Exception {
        for (String path : List.of("/room-types?size=101", "/room-types?page=-1", "/room-types?sort=password,asc",
                "/rooms?sort=code,random", "/rooms?floor=-6", "/rooms?roomTypeId=bad", "/rooms?operationalStatus=INVALID",
                "/room-types?minCapacity=0", "/room-types?minCapacity=4&maxCapacity=2")) {
            assertThat(guest().get(path).statusCode()).as(path).isEqualTo(400);
        }
    }
    @Test void enforcesAdministratorAndEmployeeMatrix() throws Exception {
        var admin = client("ADMIN"); var employee = client("EMPLEADO"); var customer = client("CLIENTE");
        var type = createType(admin, "Doble", "50", 2);
        var room = createRoom(admin, type, "101", 1, "ACTIVE", true);
        for (var denied : List.of(employee, customer)) {
            assertThat(denied.send("POST", "/room-types", typeBody("New", "10", 1)).statusCode()).isEqualTo(403);
            assertThat(denied.send("PATCH", "/room-types/"+id(type), Map.of("version", 0, "active", false)).statusCode()).isEqualTo(403);
            assertThat(denied.send("POST", "/rooms", roomBody(type, "202", 2, "ACTIVE", true)).statusCode()).isEqualTo(403);
            assertThat(denied.send("PATCH", "/rooms/"+id(room), Map.of("version", 0, "floor", 3)).statusCode()).isEqualTo(403);
        }
        assertThat(employee.get("/staff/rooms").statusCode()).isEqualTo(200);
        assertThat(employee.get("/staff/room-types").statusCode()).isEqualTo(200);
        assertThat(customer.get("/staff/rooms").statusCode()).isEqualTo(403);
        assertThat(customer.send("PATCH", "/rooms/"+id(room)+"/status", Map.of("version", 0, "operationalStatus", "MAINTENANCE")).statusCode()).isEqualTo(403);
        assertThat(employee.send("PATCH", "/rooms/"+id(room)+"/status", Map.of("version", 0, "operationalStatus", "MAINTENANCE")).statusCode()).isEqualTo(200);
        assertThat(admin.send("PATCH", "/rooms/"+id(room)+"/status", Map.of("version", 1, "operationalStatus", "ACTIVE")).statusCode()).isEqualTo(200);
    }
    @Test void anonymousRequestsCannotWriteAndMutationsStillRequireCsrfAndOrigin() throws Exception {
        var anon = guest();
        assertThat(anon.get("/staff/rooms").statusCode()).isEqualTo(401);
        assertThat(anon.send("POST", "/room-types", typeBody("Type", "10", 1)).statusCode()).isEqualTo(401);
        var admin = client("ADMIN");
        assertThat(anon.raw("POST", "/room-types", typeBody("Type", "10", 1), false, "http://localhost:3000").statusCode()).isEqualTo(403);
        assertThat(admin.raw("POST", "/room-types", typeBody("Type", "10", 1), true, "https://evil.example").statusCode()).isEqualTo(403);
        assertThat(count("room_types")).isZero();
    }
    @Test void statusEndpointRejectsAdministrativeFieldsAndAuditsAllowedChanges() throws Exception {
        var admin = client("ADMIN"); var employee = client("EMPLEADO");
        var type = createType(admin, "Doble", "50", 2);
        var room = createRoom(admin, type, "101", 1, "ACTIVE", true);
        assertThat(employee.send("PATCH", "/rooms/"+id(room)+"/status",
                Map.of("version", 0, "operationalStatus", "MAINTENANCE", "active", false)).statusCode()).isEqualTo(400);
        assertThat(employee.send("PATCH", "/rooms/"+id(room)+"/status",
                Map.of("version", 0, "operationalStatus", "MAINTENANCE")).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='ROOM_STATUS_CHANGED'", Integer.class)).isEqualTo(1);
    }
    @Test void staleEditsAreConflictsAndMissingResourcesAre404() throws Exception {
        var admin = client("ADMIN");
        var type = createType(admin, "Doble", "50", 2);
        var room = createRoom(admin, type, "101", 1, "ACTIVE", true);
        assertThat(admin.send("PATCH", "/rooms/"+id(room), Map.of("version", 0, "floor", 2)).statusCode()).isEqualTo(200);
        var stale = admin.send("PATCH", "/rooms/"+id(room), Map.of("version", 0, "floor", 3));
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(tree(stale).get("code").asText()).isEqualTo("STALE_VERSION");
        admin.send("PATCH", "/room-types/"+id(type), Map.of("version", 0, "capacity", 3));
        assertThat(admin.send("PATCH", "/room-types/"+id(type), Map.of("version", 0, "capacity", 4)).statusCode()).isEqualTo(409);
        assertThat(admin.send("PATCH", "/rooms/"+id(room), Map.of("floor", 4)).statusCode()).isEqualTo(400);
        assertThat(admin.get("/staff/rooms/"+UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(guest().get("/room-types/"+UUID.randomUUID()).statusCode()).isEqualTo(404);
    }
    @Test void concurrentHttpEditsCannotSilentlyOverwriteEachOther() throws Exception {
        var admin = client("ADMIN");
        var type = createType(admin, "Doble", "50", 2);
        var room = createRoom(admin, type, "101", 1, "ACTIVE", true);
        var results = parallel(
                () -> admin.send("PATCH", "/rooms/"+id(room), Map.of("version", 0, "floor", 2)).statusCode(),
                () -> admin.send("PATCH", "/rooms/"+id(room), Map.of("version", 0, "floor", 3)).statusCode());
        assertThat(results).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT version FROM rooms", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='ROOM_UPDATED'", Integer.class)).isEqualTo(1);
    }
    @Test void optimisticLockRejectsTwoTransactionsThatReadTheSameVersion() throws Exception {
        var admin = client("ADMIN");
        var type = createType(admin, "Doble", "50", 2);
        UUID roomId = UUID.fromString(id(createRoom(admin, type, "101", 1, "ACTIVE", true)));
        var readGate = new CyclicBarrier(2);
        Callable<Boolean> edit = () -> {
            try {
                new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    var r = rooms.findById(roomId).orElseThrow();
                    try { readGate.await(15, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
                    r.update(r.getCode(), r.getRoomType(), 2, Room.OperationalStatus.ACTIVE, true, java.time.Instant.now());
                    rooms.flush();
                });
                return true;
            } catch (org.springframework.dao.OptimisticLockingFailureException expected) { return false; }
        };
        assertThat(parallel(edit, edit)).containsExactlyInAnyOrder(true, false);
    }
    @Test void postgresEnforcesUniqueCheckAndForeignKeyConstraints() throws Exception {
        var admin = client("ADMIN");
        var type = createType(admin, "Doble", "50", 2);
        createRoom(admin, type, "101", 1, "ACTIVE", true);
        for (String sql : List.of("UPDATE room_types SET capacity=0", "UPDATE room_types SET base_price=0",
                "UPDATE room_types SET name=''", "UPDATE rooms SET floor=201", "UPDATE rooms SET operational_status='INVALID'",
                "UPDATE rooms SET room_type_id='"+UUID.randomUUID()+"'"))
            assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM room_types")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("rooms")).isEqualTo(1);
    }
    @Test void migrationAndOpenApiContainOnlyTheCurrentCatalogScope() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='3' AND success", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public'", String.class))
                .contains("room_types", "rooms", "reservations", "idempotency_requests", "payments", "refunds");
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname='public'", String.class))
                .contains("uq_room_types_name", "uq_rooms_code", "idx_rooms_type", "idx_rooms_catalog");
        var response = guest().http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v3/api-docs")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var paths = tree(response).get("paths");
        assertThat(paths.has("/api/v1/rooms/{id}/status")).isTrue();
        assertThat(paths.has("/api/v1/staff/room-types")).isTrue();
        assertThat(paths.get("/api/v1/rooms").get("post").get("security").toString()).contains("bearerAuth");
        for (String path : List.of("/api/v1/room-types", "/api/v1/room-types/{id}", "/api/v1/rooms", "/api/v1/rooms/{id}",
                "/api/v1/staff/room-types", "/api/v1/staff/room-types/{id}", "/api/v1/staff/rooms", "/api/v1/staff/rooms/{id}")) {
            var success = paths.get(path).get("get").path("responses").path("200").path("content").path("application/json").path("schema");
            assertThat(success.has("$ref")).as(path).isTrue();
        }
        var responses = paths.get("/api/v1/room-types").get("post").get("responses");
        assertThat(responses.path("201").path("content").path("application/json").path("schema").has("$ref")).isTrue();
        assertThat(responses.path("409").path("content").path("application/problem+json").path("schema").path("$ref").asText()).endsWith("/ProblemDetail");
    }

    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table, Integer.class); }
    private JsonNode tree(HttpResponse<String> r) { return json.readTree(r.body()); }
    private List<JsonNode> items(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var list = new ArrayList<JsonNode>(); tree(r).get("items").forEach(list::add); return list;
    }
    private String id(JsonNode n) { return n.get("id").asText(); }
    private Map<String,Object> typeBody(String name, String price, int capacity) {
        return new HashMap<>(Map.of("name", name, "description", "Catalog test", "capacity", capacity, "basePrice", price, "active", true));
    }
    private Map<String,Object> roomBody(JsonNode type, String code, int floor, String status, boolean active) {
        return new HashMap<>(Map.of("code", code, "roomTypeId", id(type), "floor", floor, "operationalStatus", status, "active", active));
    }
    private JsonNode createType(Client c, String name, String price, int capacity) throws Exception {
        var r = c.send("POST", "/room-types", typeBody(name, price, capacity));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201); return tree(r);
    }
    private JsonNode createRoom(Client c, JsonNode type, String code, int floor, String status, boolean active) throws Exception {
        var r = c.send("POST", "/rooms", roomBody(type, code, floor, status, active));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201); return tree(r);
    }
    private <T> List<T> parallel(Callable<T> a, Callable<T> b) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var first = executor.submit(() -> { gate.await(); return a.call(); });
            var second = executor.submit(() -> { gate.await(); return b.call(); });
            gate.countDown(); return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
    }
    private Client guest() { var c = new Client(); clients.add(c); return c; }
    private Client client(String role) throws Exception {
        var c = guest(); var login = c.send("POST", "/auth/login", Map.of("email", role.toLowerCase(Locale.ROOT)+"@example.test", "password", PASSWORD));
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200); c.access = tree(login).get("accessToken").asText(); return c;
    }
    private class Client {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(10)).build();
        String access;
        URI uri(String path) { return URI.create("http://localhost:"+port+"/api/v1"+path); }
        HttpResponse<String> get(String path) throws Exception { return raw("GET", path, null, false, null); }
        HttpResponse<String> send(String method, String path, Object body) throws Exception {
            return raw(method, path, body, true, "http://localhost:3000");
        }
        HttpResponse<String> raw(String method, String path, Object body, boolean csrf, String origin) throws Exception {
            var req = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
            if (csrf) {
                var value = tree(http.send(HttpRequest.newBuilder(uri("/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString()));
                req.header(value.get("headerName").asText(), value.get("token").asText());
            }
            if (origin != null) req.header("Origin", origin);
            if (access != null) req.header("Authorization", "Bearer "+access);
            return http.send(req.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
