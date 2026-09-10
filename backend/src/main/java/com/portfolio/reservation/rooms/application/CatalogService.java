package com.portfolio.reservation.rooms.application;

import com.portfolio.reservation.rooms.api.*;
import com.portfolio.reservation.rooms.domain.*;
import com.portfolio.reservation.rooms.infrastructure.*;
import com.portfolio.reservation.shared.api.*;
import com.portfolio.reservation.audit.AuditService;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CatalogService {
    private final RoomTypeRepository types;
    private final RoomRepository rooms;
    private final AuditService audit;
    private final Clock clock;
    private final String currency;
    private final CatalogReservationGuard reservationGuard;
    public CatalogService(RoomTypeRepository types, RoomRepository rooms, AuditService audit, Clock clock,
            @Value("${hotel.currency:PEN}") String currency, CatalogReservationGuard reservationGuard) {
        this.types = types; this.rooms = rooms; this.audit = audit; this.clock = clock;
        this.currency = Currency.getInstance(currency).getCurrencyCode();
        this.reservationGuard = reservationGuard;
    }
    public PageView<CatalogViews.PublicType> publicTypes(CatalogFilters.Types f) {
        return PageView.from(findTypes(f, true).map(t -> CatalogViews.PublicType.from(t, currency)));
    }
    public CatalogViews.PublicType publicType(UUID id) {
        return CatalogViews.PublicType.from(types.findById(id).filter(RoomType::isActive).orElseThrow(() -> missing("ROOM_TYPE")), currency);
    }
    public PageView<CatalogViews.PublicRoom> publicRooms(CatalogFilters.Rooms f) {
        return PageView.from(findRooms(f, true).map(r -> CatalogViews.PublicRoom.from(r, currency)));
    }
    public CatalogViews.PublicRoom publicRoom(UUID id) {
        return CatalogViews.PublicRoom.from(rooms.findById(id).filter(r -> r.isActive() && r.getRoomType().isActive()
                && r.getOperationalStatus() == Room.OperationalStatus.ACTIVE).orElseThrow(() -> missing("ROOM")), currency);
    }
    @PreAuthorize("hasAnyRole('ADMIN','EMPLEADO')")
    public PageView<CatalogViews.TypeView> inventoryTypes(CatalogFilters.Types f) {
        return PageView.from(findTypes(f, false).map(t -> CatalogViews.TypeView.from(t, currency)));
    }
    @PreAuthorize("hasAnyRole('ADMIN','EMPLEADO')")
    public CatalogViews.TypeView inventoryType(UUID id) { return CatalogViews.TypeView.from(type(id), currency); }
    @PreAuthorize("hasAnyRole('ADMIN','EMPLEADO')")
    public PageView<CatalogViews.RoomView> inventoryRooms(CatalogFilters.Rooms f) {
        return PageView.from(findRooms(f, false).map(r -> CatalogViews.RoomView.from(r, currency)));
    }
    @PreAuthorize("hasAnyRole('ADMIN','EMPLEADO')")
    public CatalogViews.RoomView inventoryRoom(UUID id) { return CatalogViews.RoomView.from(room(id), currency); }

    @Transactional @PreAuthorize("hasRole('ADMIN')")
    public CatalogViews.TypeView createType(CatalogRequests.TypeCreate b, Jwt jwt, UUID requestId) {
        var t = types.saveAndFlush(new RoomType(b.name(), b.description(), b.capacity(), b.basePrice(), b.active(), clock.instant()));
        record(jwt, "ROOM_TYPE_CREATED", "ROOM_TYPE", t.getId(), requestId);
        return CatalogViews.TypeView.from(t, currency);
    }
    @Transactional @PreAuthorize("hasRole('ADMIN')")
    public CatalogViews.TypeView patchType(UUID id, CatalogRequests.TypePatch b, Jwt jwt, UUID requestId) {
        var t = types.lockById(id).orElseThrow(() -> missing("ROOM_TYPE")); version(b.version(), t.getVersion());
        reservationGuard.type(id, or(b.active(), t.isActive()), or(b.capacity(), t.getCapacity()));
        boolean wasActive = t.isActive();
        t.update(or(b.name(), t.getName()), or(b.description(), t.getDescription()), or(b.capacity(), t.getCapacity()),
                or(b.basePrice(), t.getBasePrice()), or(b.active(), t.isActive()), clock.instant());
        types.flush(); // Forces @Version/UNIQUE checks before success and audit.
        record(jwt, wasActive != t.isActive() ? (t.isActive() ? "ROOM_TYPE_ACTIVATED" : "ROOM_TYPE_DEACTIVATED") : "ROOM_TYPE_UPDATED",
                "ROOM_TYPE", id, requestId);
        return CatalogViews.TypeView.from(t, currency);
    }
    @Transactional @PreAuthorize("hasRole('ADMIN')")
    public CatalogViews.RoomView createRoom(CatalogRequests.RoomCreate b, Jwt jwt, UUID requestId) {
        var r = rooms.saveAndFlush(new Room(b.code(), type(b.roomTypeId()), b.floor(), b.operationalStatus(), b.active(), clock.instant()));
        record(jwt, "ROOM_CREATED", "ROOM", r.getId(), requestId);
        return CatalogViews.RoomView.from(r, currency);
    }
    @Transactional @PreAuthorize("hasRole('ADMIN')")
    public CatalogViews.RoomView patchRoom(UUID id, CatalogRequests.RoomPatch b, Jwt jwt, UUID requestId) {
        var r = rooms.lockById(id).orElseThrow(() -> missing("ROOM")); version(b.version(), r.getVersion());
        if (Boolean.FALSE.equals(b.active()) || (b.operationalStatus() != null && b.operationalStatus() != Room.OperationalStatus.ACTIVE)
                || (b.roomTypeId() != null && !b.roomTypeId().equals(r.getRoomType().getId()))) reservationGuard.room(id);
        boolean wasActive = r.isActive();
        r.update(or(b.code(), r.getCode()), b.roomTypeId() == null ? r.getRoomType() : type(b.roomTypeId()),
                or(b.floor(), r.getFloor()), or(b.operationalStatus(), r.getOperationalStatus()), or(b.active(), r.isActive()), clock.instant());
        rooms.flush();
        record(jwt, wasActive != r.isActive() ? (r.isActive() ? "ROOM_ACTIVATED" : "ROOM_DEACTIVATED") : "ROOM_UPDATED",
                "ROOM", id, requestId);
        return CatalogViews.RoomView.from(r, currency);
    }
    @Transactional @PreAuthorize("hasAnyRole('ADMIN','EMPLEADO')")
    public CatalogViews.RoomView patchStatus(UUID id, CatalogRequests.StatusPatch b, Jwt jwt, UUID requestId) {
        var r = rooms.lockById(id).orElseThrow(() -> missing("ROOM")); version(b.version(), r.getVersion());
        if (b.operationalStatus() != Room.OperationalStatus.ACTIVE) reservationGuard.room(id);
        r.update(r.getCode(), r.getRoomType(), r.getFloor(), b.operationalStatus(), r.isActive(), clock.instant());
        rooms.flush();
        record(jwt, "ROOM_STATUS_CHANGED", "ROOM", id, requestId);
        return CatalogViews.RoomView.from(r, currency);
    }
    private Page<RoomType> findTypes(CatalogFilters.Types f, boolean visibleOnly) {
        if (f.minCapacity() != null && f.maxCapacity() != null && f.minCapacity() > f.maxCapacity())
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILTER", "La capacidad mínima no puede superar la máxima.");
        return types.findAll((root, query, cb) -> {
            var p = new ArrayList<Predicate>();
            if (visibleOnly) p.add(cb.isTrue(root.get("active")));
            if (f.active() != null) p.add(cb.equal(root.get("active"), f.active()));
            if (f.q() != null) p.add(cb.like(cb.lower(root.get("name")), pattern(f.q()), '\\'));
            if (f.minCapacity() != null) p.add(cb.ge(root.get("capacity"), f.minCapacity()));
            if (f.maxCapacity() != null) p.add(cb.le(root.get("capacity"), f.maxCapacity()));
            return cb.and(p.toArray(Predicate[]::new));
        }, PageView.request(f.page(), f.size(), f.sort(), "name", Set.of("name", "capacity", "basePrice", "id")));
    }
    private Page<Room> findRooms(CatalogFilters.Rooms f, boolean visibleOnly) {
        return rooms.findAll((root, query, cb) -> {
            var p = new ArrayList<Predicate>();
            if (visibleOnly) {
                p.add(cb.isTrue(root.get("active")));
                p.add(cb.isTrue(root.get("roomType").get("active")));
                p.add(cb.equal(root.get("operationalStatus"), Room.OperationalStatus.ACTIVE));
            }
            if (f.active() != null) p.add(cb.equal(root.get("active"), f.active()));
            if (f.operationalStatus() != null) p.add(cb.equal(root.get("operationalStatus"), f.operationalStatus()));
            if (f.roomTypeId() != null) p.add(cb.equal(root.get("roomType").get("id"), f.roomTypeId()));
            if (f.floor() != null) p.add(cb.equal(root.get("floor"), f.floor()));
            if (f.q() != null) p.add(cb.like(cb.lower(root.get("code")), pattern(f.q()), '\\'));
            return cb.and(p.toArray(Predicate[]::new));
        }, PageView.request(f.page(), f.size(), f.sort(), "code", Set.of("code", "floor", "operationalStatus", "id")));
    }
    private RoomType type(UUID id) { return types.findById(id).orElseThrow(() -> missing("ROOM_TYPE")); }
    private Room room(UUID id) { return rooms.findById(id).orElseThrow(() -> missing("ROOM")); }
    private void record(Jwt jwt, String action, String resource, UUID id, UUID requestId) {
        audit.record(UUID.fromString(jwt.getSubject()), action, resource, id, requestId);
    }
    private static <T> T or(T value, T current) { return value == null ? current : value; }
    private static String pattern(String q) {
        return "%" + q.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
    private static void version(long expected, long current) {
        if (expected != current) throw new ApiException(HttpStatus.CONFLICT, "STALE_VERSION", "Este registro cambió. Recarga antes de guardar.");
    }
    private static ApiException missing(String type) {
        return new ApiException(HttpStatus.NOT_FOUND, type + "_NOT_FOUND", "Recurso no encontrado.");
    }
}
