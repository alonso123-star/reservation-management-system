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
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;

@Testcontainers @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdministrationIT {
    @Container static final PostgreSQLContainer DATABASE=new PostgreSQLContainer("postgres:18.6-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",DATABASE::getJdbcUrl); p.add("spring.datasource.username",DATABASE::getUsername);
        p.add("spring.datasource.password",DATABASE::getPassword);
        p.add("security.auth.jwt-secret",()->Base64.getEncoder().encodeToString(new byte[32]));
        p.add("security.auth.cookie-secure",()->false); p.add("hotel.time-zone",()->"America/Lima");
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder encoder;
    static final String PASSWORD="Administration synthetic fixture 123!";
    static String hash;
    final Map<String,UUID> ids=new HashMap<>();
    final List<Client> clients=new ArrayList<>();
    @BeforeEach void prepare() {
        jdbc.execute("TRUNCATE idempotency_requests,refunds,payments,reservations,rooms,room_types,auth_rate_limits,audit_events,refresh_tokens,refresh_sessions,users");
        if(hash==null) hash=encoder.encode(PASSWORD);
        for(String role:List.of("admin","second","employee","client")) {
            UUID id=UUID.randomUUID(); ids.put(role,id);
            String name=role.equals("admin")||role.equals("second")?"ADMIN":role.equals("employee")?"EMPLEADO":"CLIENTE";
            jdbc.update("INSERT INTO users(id,role_id,name,email,password_hash,created_at,updated_at) SELECT ?,id,?,?,?,now(),now() FROM roles WHERE name=?",id,role,role+"@example.test",hash,name);
        }
    }
    @AfterEach void close() { clients.forEach(c->c.http.close()); }
    @ParameterizedTest @ValueSource(strings={"client","employee"})
    void nonAdminsCannotUseAnyAdministrativeRoute(String role) throws Exception {
        var c=login(role); String id=ids.get("client").toString();
        for(String path:List.of("/users","/users/"+id,"/admin/dashboard?from=2030-10-10&to=2030-10-13","/admin/audit-events")) {
            assertThat(c.send("GET",path,null).statusCode()).isEqualTo(403);
            assertThat(guest().send("GET",path,null).statusCode()).isEqualTo(401);
        }
        assertThat(c.send("POST","/users",createBody("blocked@example.test","ADMIN")).statusCode()).isEqualTo(403);
        assertThat(c.send("PATCH","/users/"+id+"/role",Map.of("role","ADMIN","version",0)).statusCode()).isEqualTo(403);
        assertThat(c.send("PATCH","/users/"+id+"/status",Map.of("active",false,"version",0)).statusCode()).isEqualTo(403);
        assertThat(activeAdmins()).isEqualTo(2);
    }
    @Test void usersSupportSafeDetailSearchFiltersStablePaginationAndValidation() throws Exception {
        var c=login("admin"); var all=c.send("GET","/users?size=2&sort=name,asc",null);
        assertThat(all.statusCode()).isEqualTo(200); var body=tree(all);
        assertThat(body.path("totalElements").asInt()).isEqualTo(4); assertThat(body.path("items").size()).isEqualTo(2);
        assertThat(body.path("items").get(0).path("name").asText()).isEqualTo("admin");
        assertThat(tree(c.send("GET","/users?size=2&page=1",null)).path("items").size()).isEqualTo(2);
        assertThat(tree(c.send("GET","/users?q=EMPLOYEE&role=EMPLEADO&active=true",null)).path("totalElements").asInt()).isEqualTo(1);
        assertThat(tree(c.send("GET","/users?q=%25",null)).path("totalElements").asInt()).isZero();
        for(String query:List.of("size=101","page=-1","sort=passwordHash,asc","role=ROOT")) assertThat(c.send("GET","/users?"+query,null).statusCode()).isEqualTo(400);
        var detail=c.send("GET","/users/"+ids.get("client"),null); assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).doesNotContain("password","securityVersion","token","cookie");
        assertThat(tree(detail).path("version").asInt()).isZero();
        assertThat(c.send("GET","/users/"+UUID.randomUUID(),null).statusCode()).isEqualTo(404);
        assertThat(c.send("GET","/users/me",null).statusCode()).isEqualTo(200);
    }
    @ParameterizedTest @ValueSource(strings={"CLIENTE","EMPLEADO","ADMIN"})
    void createsEveryPermittedRoleWithNormalizedEmailAndSafeAudit(String role) throws Exception {
        var c=login("admin"); var response=c.send("POST","/users",createBody("New.User@EXAMPLE.TEST",role));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201); var value=tree(response); UUID id=UUID.fromString(value.path("id").asText());
        assertThat(value.path("email").asText()).isEqualTo("new.user@example.test"); assertThat(value.path("role").asText()).isEqualTo(role);
        assertThat(value.path("active").asBoolean()).isTrue(); assertThat(response.body()).doesNotContain(PASSWORD,"password","securityVersion");
        assertThat(encoder.matches(PASSWORD,jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,id))).isTrue();
        assertThat(jdbc.queryForObject("SELECT changes->>'newRole' FROM audit_events WHERE action='USER_CREATED' AND resource_id=?",String.class,id)).isEqualTo(role);
        assertThat(c.send("POST","/users",createBody("new.user@example.test",role)).statusCode()).isEqualTo(409);
    }
    @Test void rejectsInvalidCredentialsRolesAndMassAssignmentWithoutWriting() throws Exception {
        var c=login("admin"); var base=createBody("new@example.test","CLIENTE");
        for(var pair:List.of(Map.entry("password","short"),Map.entry("password","é".repeat(40)),Map.entry("role","ROOT"),Map.entry("name"," "),Map.entry("email","bad"))) {
            var body=new HashMap<String,Object>(base); body.put(pair.getKey(),pair.getValue()); assertThat(c.send("POST","/users",body).statusCode()).isEqualTo(400);
        }
        for(String key:List.of("active","id","version","securityVersion","passwordHash","createdAt")) {
            var body=new HashMap<String,Object>(base); body.put(key,"manipulated"); assertThat(c.send("POST","/users",body).statusCode()).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users",Long.class)).isEqualTo(4);
    }
    @Test void roleChangeImmediatelyRevokesAccessRefreshAndRejectsStaleVersion() throws Exception {
        var admin=login("admin"); var employee=login("employee"); UUID id=ids.get("employee");
        var changed=admin.send("PATCH","/users/"+id+"/role",Map.of("role","CLIENTE","version",0)); assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(tree(changed).path("version").asLong()).isEqualTo(1);
        assertThat(employee.send("GET","/users/me",null).statusCode()).isEqualTo(401);
        employee.access=null; assertThat(employee.send("POST","/auth/refresh",null).statusCode()).isEqualTo(401);
        assertThat(admin.send("PATCH","/users/"+id+"/role",Map.of("role","ADMIN","version",0)).statusCode()).isEqualTo(409);
        var again=login("employee"); assertThat(again.send("GET","/users",null).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT security_version FROM users WHERE id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void statusChangePreservesHistoryAndActivationDoesNotRestoreSessions() throws Exception {
        seedMetrics(); var admin=login("admin"); var client=login("client"); UUID id=ids.get("client");
        assertThat(admin.send("PATCH","/users/"+id+"/status",Map.of("active",false,"version",0)).statusCode()).isEqualTo(200);
        assertThat(client.send("GET","/users/me",null).statusCode()).isEqualTo(401);
        assertThat(guest().signIn("client").statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments",Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_sessions WHERE user_id=?",Long.class,id)).isEqualTo(1);
        assertThat(admin.send("PATCH","/users/"+id+"/status",Map.of("active",true,"version",1)).statusCode()).isEqualTo(200);
        assertThat(client.send("GET","/users/me",null).statusCode()).isEqualTo(401);
        assertThat(guest().signIn("client").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action IN ('USER_ACTIVATED','USER_DEACTIVATED')",Long.class)).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"role","status"})
    void lastActiveAdminCannotBeRemovedAndSelfChangeWithAnotherAdminRevokesSelf(String operation) throws Exception {
        var a=login("admin"); UUID id=ids.get("admin");
        var body=operation.equals("role")?Map.of("role","EMPLEADO","version",0):Map.of("active",false,"version",0);
        jdbc.update("UPDATE users SET active=false WHERE id=?",ids.get("second"));
        var denied=a.send("PATCH","/users/"+id+"/"+operation,body); assertThat(denied.statusCode()).isEqualTo(409); assertThat(denied.body()).contains("LAST_ACTIVE_ADMIN");
        jdbc.update("UPDATE users SET active=true WHERE id=?",ids.get("second"));
        assertThat(a.send("PATCH","/users/"+id+"/"+operation,body).statusCode()).isEqualTo(200);
        assertThat(a.send("GET","/users",null).statusCode()).isEqualTo(401); assertThat(activeAdmins()).isEqualTo(1);
    }
    @ParameterizedTest @CsvSource({"role,role","status,status","role,status","status,role"})
    void independentHttpTransactionsNeverRemoveBothActiveAdmins(String firstOp,String secondOp) throws Exception {
        var a=login("admin"); var b=login("second");
        jdbc.execute("CREATE FUNCTION pause_admin() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action LIKE 'USER_%' THEN PERFORM pg_advisory_xact_lock(808080); END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER pause_admin BEFORE INSERT ON audit_events FOR EACH ROW EXECUTE FUNCTION pause_admin()");
        try(var gate=jdbc.getDataSource().getConnection(); var executor=Executors.newFixedThreadPool(2)) {
            gate.createStatement().execute("SELECT pg_advisory_lock(808080)");
            var first=executor.submit(()->a.send("PATCH","/users/"+ids.get("admin")+"/"+firstOp,change(firstOp,0)));
            Future<HttpResponse<String>> second;
            try {
                awaitLocks(1); second=executor.submit(()->b.send("PATCH","/users/"+ids.get("second")+"/"+secondOp,change(secondOp,0))); awaitLocks(2);
            } finally { gate.createStatement().execute("SELECT pg_advisory_unlock(808080)"); }
            assertThat(first.get(30,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            var rejected=second.get(30,TimeUnit.SECONDS); assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(409); assertThat(rejected.body()).contains("LAST_ACTIVE_ADMIN");
            assertThat(activeAdmins()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action LIKE 'USER_%'",Long.class)).isEqualTo(1);
        } finally { jdbc.execute("DROP TRIGGER pause_admin ON audit_events"); jdbc.execute("DROP FUNCTION pause_admin()"); }
    }
    @Test void auditFailureRollsBackUserVersionAndSessionRevocation() throws Exception {
        var a=login("admin"); var c=login("client"); UUID id=ids.get("client");
        jdbc.execute("CREATE FUNCTION fail_admin() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action LIKE 'USER_%' THEN RAISE EXCEPTION 'Synthetic failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_admin BEFORE INSERT ON audit_events FOR EACH ROW EXECUTE FUNCTION fail_admin()");
        try {
            var failure=a.send("PATCH","/users/"+id+"/status",Map.of("active",false,"version",0)); assertThat(failure.statusCode()).isEqualTo(500);
            assertThat(failure.body()).doesNotContain("Synthetic failure","INSERT","stackTrace");
            assertThat(c.send("GET","/users/me",null).statusCode()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT security_version+version FROM users WHERE id=?",Long.class,id)).isZero();
        } finally { jdbc.execute("DROP TRIGGER fail_admin ON audit_events"); jdbc.execute("DROP FUNCTION fail_admin()"); }
    }
    @Test void simultaneousEditorsCannotOverwriteTheSameUserVersion() throws Exception {
        var a=login("admin"); var b=login("second"); UUID id=ids.get("client");
        try(var executor=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var first=executor.submit(()->{ gate.await(); return a.send("PATCH","/users/"+id+"/role",Map.of("role","EMPLEADO","version",0)); });
            var second=executor.submit(()->{ gate.await(); return b.send("PATCH","/users/"+id+"/status",Map.of("active",false,"version",0)); });
            gate.countDown(); var results=List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));
            assertThat(results.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200,409);
            assertThat(results.stream().filter(r->r.statusCode()==409).findFirst().orElseThrow().body()).contains("USER_VERSION_CONFLICT");
            assertThat(jdbc.queryForObject("SELECT version FROM users WHERE id=?",Long.class,id)).isEqualTo(1);
        }
    }
    @Test void unchangedRoleOrStatusDoesNotRevokeOrDuplicateAudit() throws Exception {
        var a=login("admin"); var c=login("client"); UUID id=ids.get("client");
        assertThat(a.send("PATCH","/users/"+id+"/role",Map.of("role","CLIENTE","version",0)).statusCode()).isEqualTo(200);
        assertThat(a.send("PATCH","/users/"+id+"/status",Map.of("active",true,"version",0)).statusCode()).isEqualTo(200);
        assertThat(c.send("GET","/users/me",null).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT security_version+version FROM users WHERE id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action LIKE 'USER_%'",Long.class)).isZero();
    }
    @Test void auditFailureAlsoRollsBackCreationAndRoleChange() throws Exception {
        var a=login("admin"); UUID id=ids.get("client");
        jdbc.execute("CREATE FUNCTION fail_admin_create() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action LIKE 'USER_%' THEN RAISE EXCEPTION 'Synthetic failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_admin_create BEFORE INSERT ON audit_events FOR EACH ROW EXECUTE FUNCTION fail_admin_create()");
        try {
            assertThat(a.send("POST","/users",createBody("rollback@example.test","ADMIN")).statusCode()).isEqualTo(500);
            assertThat(a.send("PATCH","/users/"+id+"/role",Map.of("role","ADMIN","version",0)).statusCode()).isEqualTo(500);
            assertThat(activeAdmins()).isEqualTo(2); assertThat(jdbc.queryForObject("SELECT count(*) FROM users",Long.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT security_version+version FROM users WHERE id=?",Long.class,id)).isZero();
        } finally { jdbc.execute("DROP TRIGGER fail_admin_create ON audit_events"); jdbc.execute("DROP FUNCTION fail_admin_create()"); }
    }
    @Test void dashboardUsesExactHotelBoundariesScheduledOccupancyAndIndependentCashFlows() throws Exception {
        seedMetrics(); var r=login("admin").send("GET","/admin/dashboard?from=2030-10-10&to=2030-10-13",null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200); var d=tree(r);
        assertThat(d.path("reservationsCreated").asLong()).isEqualTo(1); assertThat(d.path("arrivals").asLong()).isEqualTo(1); assertThat(d.path("departures").asLong()).isEqualTo(1);
        assertThat(d.path("roomNightsOccupied").asLong()).isEqualTo(3); assertThat(d.path("roomNightsAvailable").asLong()).isEqualTo(6);
        assertThat(d.path("occupancyPercent").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(d.path("approvedPayments").decimalValue()).isEqualByComparingTo("300.30"); assertThat(d.path("refunds").decimalValue()).isEqualByComparingTo("80.10");
        assertThat(d.path("netRevenue").decimalValue()).isEqualByComparingTo("220.20"); assertThat(d.path("hotelTimeZone").asText()).isEqualTo("America/Lima");
        assertThat(d.path("currency").asText()).isEqualTo("PEN");
    }
    @Test void emptyZeroInventoryInvalidPeriodsAndDifferentCurrenciesAreExplicit() throws Exception {
        var a=login("admin"); String path="/admin/dashboard?from=2030-10-10&to=2030-10-13";
        var d=tree(a.send("GET",path,null)); assertThat(d.path("occupancyPercent").isNull()).isTrue(); assertThat(d.path("netRevenue").decimalValue()).isEqualByComparingTo("0");
        for(String period:List.of("from=2030-10-10&to=2030-10-10","from=2030-10-11&to=2030-10-10","from=2030-01-01&to=2032-01-01","from=invalid&to=2030-10-10","from=2030-10-10")) assertThat(a.send("GET","/admin/dashboard?"+period,null).statusCode()).isEqualTo(400);
        seedMetrics(); jdbc.update("UPDATE payments SET currency='USD' WHERE result='APPROVED'");
        var conflict=a.send("GET",path,null); assertThat(conflict.statusCode()).isEqualTo(409); assertThat(conflict.body()).contains("METRIC_CURRENCY_MISMATCH");
    }
    @Test void refundedOnlyPeriodCanBeNegativeAndCancelledStaysDoNotCount() throws Exception {
        seedMetrics(); var a=login("admin");
        var d=tree(a.send("GET","/admin/dashboard?from=2030-10-11&to=2030-10-12",null));
        assertThat(d.path("approvedPayments").decimalValue()).isEqualByComparingTo("0"); assertThat(d.path("netRevenue").decimalValue()).isEqualByComparingTo("-80.10");
        jdbc.update("UPDATE reservations SET status='NO_SHOW' WHERE status='CONFIRMED'");
        d=tree(a.send("GET","/admin/dashboard?from=2030-10-10&to=2030-10-13",null));
        assertThat(d.path("arrivals").asLong()).isZero(); assertThat(d.path("roomNightsOccupied").asLong()).isEqualTo(2);
        jdbc.update("UPDATE rooms SET active=false");
        d=tree(a.send("GET","/admin/dashboard?from=2030-10-10&to=2030-10-13",null));
        assertThat(d.path("roomNightsAvailable").asLong()).isZero(); assertThat(d.path("roomNightsOccupied").asLong()).isZero(); assertThat(d.path("occupancyPercent").isNull()).isTrue();
    }
    @Test void auditIncludesHistoricalPhaseEventsWithoutInventingPayloads() throws Exception {
        var a=login("admin");
        for(String action:List.of("CLIENT_REGISTERED","ROOM_CREATED","RESERVATION_CREATED","PAYMENT_APPROVED","REFUND_CREATED","RESERVATION_CHECKED_IN","RESERVATION_CHECKED_OUT","RESERVATION_NO_SHOW")) {
            jdbc.update("INSERT INTO audit_events(id,action,resource_type,occurred_at,request_id) VALUES(?,?,'HISTORICAL','2030-10-10T05:00:00Z',?)",UUID.randomUUID(),action,UUID.randomUUID());
            var r=a.send("GET","/admin/audit-events?action="+action,null); assertThat(r.statusCode()).isEqualTo(200);
            assertThat(tree(r).path("items").get(0).path("changes").size()).isZero();
        }
    }
    @Test void auditFiltersPaginationHistoryAndClosedWhitelistNeverExposeArbitraryJson() throws Exception {
        var a=login("admin"); UUID event=UUID.randomUUID(),request=UUID.randomUUID();
        jdbc.update("INSERT INTO audit_events(id,actor_id,action,resource_type,resource_id,occurred_at,request_id,changes) VALUES(?,?,'USER_ROLE_CHANGED','USER',?,'2030-10-10T05:00:00Z',?,CAST(? AS jsonb))",event,ids.get("admin"),ids.get("client"),request,"{\"oldRole\":\"CLIENTE\",\"newRole\":\"EMPLEADO\",\"password\":\"synthetic-must-not-appear\",\"newActive\":{\"token\":\"hidden\"}}");
        String filter="/admin/audit-events?actorId="+ids.get("admin")+"&action=USER_ROLE_CHANGED&resource=USER&resourceId="+ids.get("client")+"&requestId="+request+"&from=2030-10-10T05:00:00Z&to=2030-10-11T05:00:00Z&size=1&sort=occurredAt,desc";
        var r=a.send("GET",filter,null); assertThat(r.statusCode()).isEqualTo(200); assertThat(tree(r).path("totalElements").asInt()).isEqualTo(1);
        assertThat(r.body()).contains("oldRole","CLIENTE","EMPLEADO").doesNotContain("password","synthetic-must-not-appear","token","newActive");
        assertThat(tree(a.send("GET",filter+"&page=1",null)).path("items").size()).isZero();
        assertThat(tree(a.send("GET","/admin/audit-events?action=LOGIN_SUCCEEDED",null)).path("totalElements").asInt()).isEqualTo(1);
        assertThat(a.send("GET","/admin/audit-events?sort=changes,asc",null).statusCode()).isEqualTo(400);
        assertThat(a.send("GET","/admin/audit-events?from=2030-10-11T00:00:00Z&to=2030-10-10T00:00:00Z",null).statusCode()).isEqualTo(400);
    }
    @Test void upgradeV5KeepsHistoricalAuditAndOpenApiDocumentsAdminContracts() throws Exception {
        try(var upgrade=new PostgreSQLContainer("postgres:18.6-alpine")) {
            upgrade.start(); org.flywaydb.core.Flyway.configure().dataSource(upgrade.getJdbcUrl(),upgrade.getUsername(),upgrade.getPassword()).target("5").load().migrate();
            try(var c=DriverManager.getConnection(upgrade.getJdbcUrl(),upgrade.getUsername(),upgrade.getPassword())) {
                c.createStatement().execute("INSERT INTO audit_events VALUES(gen_random_uuid(),null,'LOGIN_FAILED','AUTH',null,now(),gen_random_uuid())");
            }
            var flyway=org.flywaydb.core.Flyway.configure().dataSource(upgrade.getJdbcUrl(),upgrade.getUsername(),upgrade.getPassword()).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1); flyway.validate();
            try(var c=DriverManager.getConnection(upgrade.getJdbcUrl(),upgrade.getUsername(),upgrade.getPassword())) {
                var rs=c.createStatement().executeQuery("SELECT action,changes::text FROM audit_events"); assertThat(rs.next()).isTrue(); assertThat(rs.getString(1)).isEqualTo("LOGIN_FAILED"); assertThat(rs.getString(2)).isEqualTo("{}");
            }
        }
        var c=guest(); var document=json.readTree(c.http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/v3/api-docs")).GET().build(),HttpResponse.BodyHandlers.ofString()).body());
        var docs=document.path("paths");
        for(String path:List.of("/users","/users/{id}","/users/{id}/role","/users/{id}/status","/admin/dashboard","/admin/audit-events")) assertThat(docs.has("/api/v1"+path)).isTrue();
        assertThat(docs.path("/api/v1/users").path("post").path("responses").has("201")).isTrue();
        assertThat(docs.path("/api/v1/users").path("post").path("requestBody").path("content").path("application/json").path("schema").path("$ref").asText()).endsWith("/AdminUserCreate");
        var schemas=document.path("components").path("schemas");
        var userProperties=schemas.path("AdminUserCreate").path("properties");
        assertThat(userProperties.has("role")).isTrue(); assertThat(userProperties.has("roomId")).isFalse();
        assertThat(userProperties.path("password").path("writeOnly").asBoolean()).isTrue();
        String reservationRef=docs.path("/api/v1/reservations").path("post").path("requestBody").path("content").path("application/json").path("schema").path("$ref").asText();
        var reservationProperties=schemas.path(reservationRef.substring(reservationRef.lastIndexOf('/')+1)).path("properties");
        assertThat(reservationProperties.has("roomId")).isTrue(); assertThat(reservationProperties.has("password")).isFalse();
        for(String path:List.of("/users","/users/{id}","/users/{id}/role","/users/{id}/status","/admin/dashboard","/admin/audit-events")) {
            var operation=docs.path("/api/v1"+path).path(path.endsWith("/role")||path.endsWith("/status")?"patch":"get");
            assertThat(operation.path("security").toString()).contains("bearerAuth");
            for(String code:List.of("200","400","401","403","404","409")) assertThat(operation.path("responses").has(code)).isTrue();
            assertThat(operation.path("responses").path("200").path("content").path("application/json").has("schema")).isTrue();
        }
    }
    private void seedMetrics() {
        UUID type=UUID.randomUUID(),r1=UUID.randomUUID(),r2=UUID.randomUUID(),r3=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID(),p1=UUID.randomUUID(),p2=UUID.randomUUID();
        jdbc.update("INSERT INTO room_types(id,name,description,capacity,base_price,created_at,updated_at) VALUES(?,'Metrics','Synthetic',2,100.10,now(),now())",type);
        for(UUID room:List.of(r1,r2,r3)) jdbc.update("INSERT INTO rooms(id,room_type_id,code,floor,operational_status,created_at,updated_at) VALUES(?,?,?,1,?,now(),now())",room,type,room.toString().substring(0,20).toUpperCase(Locale.ROOT),room.equals(r3)?"MAINTENANCE":"ACTIVE");
        jdbc.update("INSERT INTO reservations(id,code,customer_id,created_by,room_id,check_in,check_out,guests,status,agreed_nightly_rate,total_amount,currency,created_at,updated_at) VALUES(?,?,?,?,?,'2030-10-09','2030-10-12',2,'CHECKED_OUT',100.10,300.30,'PEN','2030-10-10T05:00:00Z',now()),(?,?,?,?,?,'2030-10-12','2030-10-14',2,'CONFIRMED',40.05,80.10,'PEN','2030-10-13T05:00:00Z',now())",a,"M-A",ids.get("client"),ids.get("client"),r1,b,"M-B",ids.get("client"),ids.get("client"),r2);
        jdbc.update("INSERT INTO payments(id,reservation_id,amount,currency,result,simulated_reference,actor_id,created_at) VALUES(?,?,300.30,'PEN','APPROVED',?,?, '2030-10-10T05:00:00Z'),(?,?,80.10,'PEN','APPROVED',?,?,'2030-10-10T04:59:59Z'),(?,?,300.30,'PEN','DECLINED',?,?,'2030-10-11T00:00:00Z')",p1,a,"SIM-"+p1,ids.get("client"),p2,b,"SIM-"+p2,ids.get("client"),UUID.randomUUID(),a,"SIM-declined",ids.get("client"));
        jdbc.update("INSERT INTO refunds(id,payment_id,amount,currency,reason,actor_id,created_at) VALUES(?,?,80.10,'PEN','Synthetic historical refund',?,'2030-10-11T05:00:00Z')",UUID.randomUUID(),p2,ids.get("admin"));
    }
    private long activeAdmins() { return jdbc.queryForObject("SELECT count(*) FROM users u JOIN roles r ON r.id=u.role_id WHERE u.active AND r.name='ADMIN'",Long.class); }
    private void awaitLocks(int number) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<end) { if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock'",Integer.class)>=number) return; Thread.sleep(25); }
        fail("Expected independent concurrent PostgreSQL lock wait");
    }
    private Map<String,Object> change(String kind,long version) { return kind.equals("role")?Map.of("role","EMPLEADO","version",version):Map.of("active",false,"version",version); }
    private Map<String,Object> createBody(String email,String role) { return Map.of("name","Synthetic user","email",email,"password",PASSWORD,"role",role); }
    private JsonNode tree(HttpResponse<String> r) { return json.readTree(r.body()); }
    private Client guest() { var c=new Client(); clients.add(c); return c; }
    private Client login(String name) throws Exception { var c=guest(); var r=c.signIn(name); assertThat(r.statusCode()).as(r.body()).isEqualTo(200); return c; }
    private class Client {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(10)).build();
        String access;
        HttpResponse<String> signIn(String name) throws Exception { access=null; var r=send("POST","/auth/login",Map.of("email",name+"@example.test","password",PASSWORD)); if(r.statusCode()==200) access=tree(r).path("accessToken").asText(); return r; }
        HttpResponse<String> send(String method,String path,Object body) throws Exception {
            var r=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(40)).header("Content-Type","application/json");
            if(access!=null) r.header("Authorization","Bearer "+access);
            if(!method.equals("GET")) { r.header("Origin","http://localhost:3000"); if(access==null) { var csrf=tree(send("GET","/auth/csrf",null)); r.header(csrf.path("headerName").asText(),csrf.path("token").asText()); } }
            return http.send(r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
