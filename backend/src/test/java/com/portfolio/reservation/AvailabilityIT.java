package com.portfolio.reservation;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AvailabilityIT {
    @Container static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18.6-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", DATABASE::getJdbcUrl);
        p.add("spring.datasource.username", DATABASE::getUsername);
        p.add("spring.datasource.password", DATABASE::getPassword);
        p.add("security.auth.jwt-secret", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    HttpClient client;
    UUID doubleType;
    static final String STAY = "checkIn=2026-10-10&checkOut=2026-10-12&guests=2";

    @BeforeEach void prepare() {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        jdbc.execute("TRUNCATE idempotency_requests, refunds, payments, reservations, rooms, room_types");
        doubleType = type("Doble", 2, "125.50", true);
        room("A01", doubleType, "ACTIVE", true);
        room("A02", doubleType, "ACTIVE", true);
        room("INACTIVE", doubleType, "ACTIVE", false);
        room("MAINTENANCE", doubleType, "MAINTENANCE", true);
        room("OUT", doubleType, "OUT_OF_SERVICE", true);
        room("HIDDEN", type("Oculto", 5, "1.00", false), "ACTIVE", true);
        room("SMALL", type("Individual", 1, "50.00", true), "ACTIVE", true);
        room("B01", type("Suite", 4, "200.01", true), "ACTIVE", true);
    }
    @AfterEach void close() { client.close(); }

    @Test void anonymouslyFiltersEligibilityAndCapacityBeforePaginationAndCount() throws Exception {
        var result = search(STAY + "&size=2");
        assertThat(codes(result)).containsExactly("A01", "A02");
        assertThat(result.path("totalElements").asInt()).isEqualTo(3);
        assertThat(result.path("totalPages").asInt()).isEqualTo(2);
        assertThat(result.path("items").get(0).has("active")).isFalse();
        assertThat(result.path("items").get(0).path("roomType").has("version")).isFalse();
        assertThat(codes(search(STAY.replace("guests=2", "guests=3")))).containsExactly("B01");
        assertThat(codes(search(STAY.replace("guests=2", "guests=5")))).isEmpty();
    }
    @Test void calculatesTwoCalendarNightsAndExactDecimalEstimate() throws Exception {
        var item = search(STAY).path("items").get(0);
        assertThat(item.path("nights").asLong()).isEqualTo(2);
        assertThat(item.path("estimatedTotal").decimalValue()).isEqualByComparingTo("251.00");
        assertThat(item.path("roomType").path("currency").asText()).isEqualTo("PEN");
        var suite = search(STAY + "&minPrice=200.01").path("items").get(0);
        assertThat(suite.path("estimatedTotal").decimalValue()).isEqualByComparingTo("400.02");
    }
    @Test void acceptsOneNightAndPastDatesWithoutInventingStayRestrictions() throws Exception {
        var item = search("checkIn=2020-02-28&checkOut=2020-02-29&guests=2").path("items").get(0);
        assertThat(item.path("nights").asLong()).isEqualTo(1);
        assertThat(item.path("estimatedTotal").decimalValue()).isEqualByComparingTo("125.50");
        assertThat(search("checkIn=2024-02-28&checkOut=2024-03-01&guests=2").path("items").get(0).path("nights").asLong()).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings = {
        "checkIn=2026-10-10&checkOut=2026-10-10&guests=2",
        "checkIn=2026-10-12&checkOut=2026-10-10&guests=2",
        "checkOut=2026-10-12&guests=2", "checkIn=2026-10-10&guests=2",
        "checkIn=2026-10-10&checkOut=2026-10-12", "",
        "checkIn=bad&checkOut=2026-10-12&guests=2",
        "checkIn=2026-02-30&checkOut=2026-03-02&guests=2",
        "checkIn=2026-10-10T00:00:00Z&checkOut=2026-10-12&guests=2",
        "checkIn=2026-10-10&checkOut=2026-10-12&guests=0",
        "checkIn=2026-10-10&checkOut=2026-10-12&guests=-1",
        "checkIn=2026-10-10&checkOut=2026-10-12&guests=1.5",
        "checkIn=2026-10-10&checkOut=2026-10-12&guests=2147483648"
    }) void rejectsInvalidStayWithConsistentProblemDetail(String query) throws Exception { invalid(query); }

    @ParameterizedTest @ValueSource(strings = {
        "&roomTypeId=bad", "&minPrice=-1", "&maxPrice=-1", "&minPrice=1.001", "&minPrice=10000000000",
        "&minPrice=150&maxPrice=100", "&maxPrice=bad", "&page=-1", "&page=100001", "&size=0", "&size=101",
        "&page=1.5", "&sort=code", "&sort=code,up", "&sort=roomType.basePrice,asc", "&sort=createdAt,asc",
        "&sort=code,asc,id,desc"
    }) void rejectsInvalidFiltersAndPagination(String extra) throws Exception { invalid(STAY + extra); }

    @Test void combinesTypeCapacityAndInclusiveNightlyPriceBounds() throws Exception {
        assertThat(codes(search(STAY + "&roomTypeId=" + doubleType + "&minPrice=125.50&maxPrice=125.50")))
                .containsExactly("A01", "A02");
        assertThat(codes(search(STAY + "&minPrice=125.51&maxPrice=200.01"))).containsExactly("B01");
        assertThat(codes(search(STAY + "&maxPrice=125.49"))).isEmpty();
        assertThat(codes(search(STAY + "&minPrice=0"))).hasSize(3);
        assertThat(codes(search(STAY + "&roomTypeId=" + UUID.randomUUID()))).isEmpty();
    }
    @Test void sortsByTypePriceCapacityAndStableIdAcrossSqlPages() throws Exception {
        assertThat(codes(search(STAY + "&sort=basePrice,desc"))).startsWith("B01");
        assertThat(codes(search(STAY + "&sort=capacity,desc"))).startsWith("B01");
        var expected = jdbc.queryForList("SELECT id::text FROM rooms WHERE code IN ('A01','A02') ORDER BY id", String.class);
        var first = search(STAY + "&sort=basePrice,asc&size=1&page=0");
        var second = search(STAY + "&sort=basePrice,asc&size=1&page=1");
        assertThat(first.path("items").get(0).path("id").asText()).isEqualTo(expected.get(0));
        assertThat(second.path("items").get(0).path("id").asText()).isEqualTo(expected.get(1));
        assertThat(second.path("page").asInt()).isEqualTo(1);
        assertThat(second.path("totalElements").asInt()).isEqualTo(3);
        assertThat(codes(search(STAY + "&page=100000&size=100"))).isEmpty();
        assertThat(search(STAY).path("size").asInt()).isEqualTo(20);
    }
    @Test void preservesAdministrativeProtectionAndReadOnlySchema() throws Exception {
        assertThat(get("/api/v1/staff/rooms").statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/staff/room-types").statusCode()).isEqualTo(401);
        search(STAY);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rooms", Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class)).containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public'", String.class)).contains("reservations", "idempotency_requests", "payments", "refunds");
    }
    @Test void documentsAllParametersPublicAccessSuccessAndProblemResponses() throws Exception {
        var document = json.readTree(get("/v3/api-docs").body());
        var operation = document.path("paths").path("/api/v1/rooms/availability").path("get");
        var names = new ArrayList<String>();
        var required = new ArrayList<String>();
        operation.path("parameters").forEach(p -> {
            names.add(p.path("name").asText());
            if (p.path("required").asBoolean()) required.add(p.path("name").asText());
        });
        assertThat(names).containsExactlyInAnyOrder("checkIn", "checkOut", "guests", "roomTypeId", "minPrice", "maxPrice", "page", "size", "sort");
        assertThat(required).containsExactlyInAnyOrder("checkIn", "checkOut", "guests");
        assertThat(operation.path("security").isMissingNode() || operation.path("security").isEmpty()).isTrue();
        assertThat(operation.path("responses").path("200").path("content").has("application/json")).isTrue();
        assertThat(operation.path("responses").path("400").path("content").has("application/problem+json")).isTrue();
        assertThat(document.path("components").path("schemas").path("AvailabilityView").path("properties").has("estimatedTotal")).isTrue();
    }
    private UUID type(String name, int capacity, String price, boolean active) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO room_types(id,name,description,capacity,base_price,active,created_at,updated_at) VALUES(?,?,'Test',?,?,?,now(),now())", id, name, capacity, new BigDecimal(price), active);
        return id;
    }
    private void room(String code, UUID type, String status, boolean active) {
        jdbc.update("INSERT INTO rooms(id,code,room_type_id,floor,operational_status,active,created_at,updated_at) VALUES(?,?,?,1,?,?,now(),now())", UUID.randomUUID(), code, type, status, active);
    }
    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode search(String query) throws Exception {
        var response = get("/api/v1/rooms/availability?" + query);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
    private void invalid(String query) throws Exception {
        var response = get("/api/v1/rooms/availability?" + query);
        assertThat(response.statusCode()).as(query + " => " + response.body()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        var body = json.readTree(response.body());
        assertThat(body.path("status").asInt()).isEqualTo(400);
        assertThat(body.path("code").asText()).isNotBlank();
        assertThat(body.path("requestId").asText()).isNotBlank();
    }
    private List<String> codes(JsonNode page) {
        var codes = new ArrayList<String>();
        page.path("items").forEach(item -> codes.add(item.path("code").asText()));
        return codes;
    }
}
