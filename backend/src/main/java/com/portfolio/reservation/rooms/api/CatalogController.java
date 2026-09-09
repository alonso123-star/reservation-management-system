package com.portfolio.reservation.rooms.api;

import com.portfolio.reservation.rooms.application.CatalogService;
import com.portfolio.reservation.shared.api.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1", produces = "application/json")
@Tag(name = "Catalog", description = "Public catalog and protected inventory. Mutations require a bearer JWT and allowed Origin; the frontend also sends CSRF protection.")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid input, filter or pagination; code, requestId and optional errors fields",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "401", description = "Authentication required or invalid bearer",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "403", description = "Insufficient role or invalid CSRF/Origin",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "404", description = "Missing resource or hidden public item",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "409", description = "TYPE_NAME_TAKEN, ROOM_CODE_TAKEN or STALE_VERSION",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
public class CatalogController {
    private final CatalogService catalog;
    public CatalogController(CatalogService catalog) { this.catalog = catalog; }

    @GetMapping("/room-types")
    @ApiResponse(responseCode = "200", description = "Page of public types")
    @Operation(summary = "List active public room types", description = "Public, including anonymous visitors. Filters never expose inactive types.")
    public PageView<CatalogViews.PublicType> types(@Valid @ParameterObject @ModelAttribute CatalogFilters.Types filter) {
        return catalog.publicTypes(filter);
    }
    @GetMapping("/room-types/{id}")
    @ApiResponse(responseCode = "200", description = "Public type")
    @Operation(summary = "Read an active public room type")
    public CatalogViews.PublicType type(@PathVariable UUID id) { return catalog.publicType(id); }
    @GetMapping("/rooms")
    @ApiResponse(responseCode = "200", description = "Page of public rooms")
    @Operation(summary = "List visible public rooms", description = "Only active rooms with ACTIVE operational status and active types. Does not represent availability by date.")
    public PageView<CatalogViews.PublicRoom> rooms(@Valid @ParameterObject @ModelAttribute CatalogFilters.Rooms filter) {
        return catalog.publicRooms(filter);
    }
    @GetMapping("/rooms/{id}")
    @ApiResponse(responseCode = "200", description = "Public room")
    @Operation(summary = "Read a visible public room")
    public CatalogViews.PublicRoom room(@PathVariable UUID id) { return catalog.publicRoom(id); }

    @GetMapping("/staff/room-types") @PreAuthorize("hasAnyRole('ADMIN','EMPLEADO')") @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Page of administrative types")
    @Operation(summary = "List complete type inventory", description = "ADMIN or EMPLEADO. Includes inactive records and versions.")
    public PageView<CatalogViews.TypeView> inventoryTypes(@Valid @ParameterObject @ModelAttribute CatalogFilters.Types filter) {
        return catalog.inventoryTypes(filter);
    }
    @GetMapping("/staff/room-types/{id}") @PreAuthorize("hasAnyRole('ADMIN','EMPLEADO')") @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Administrative type")
    @Operation(summary = "Read administrative type data", description = "ADMIN or EMPLEADO.")
    public CatalogViews.TypeView inventoryType(@PathVariable UUID id) { return catalog.inventoryType(id); }
    @GetMapping("/staff/rooms") @PreAuthorize("hasAnyRole('ADMIN','EMPLEADO')") @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Page of administrative rooms")
    @Operation(summary = "List complete room inventory", description = "ADMIN or EMPLEADO. Includes inactive and non-operational rooms.")
    public PageView<CatalogViews.RoomView> inventoryRooms(@Valid @ParameterObject @ModelAttribute CatalogFilters.Rooms filter) {
        return catalog.inventoryRooms(filter);
    }
    @GetMapping("/staff/rooms/{id}") @PreAuthorize("hasAnyRole('ADMIN','EMPLEADO')") @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Administrative room")
    @Operation(summary = "Read administrative room data", description = "ADMIN or EMPLEADO.")
    public CatalogViews.RoomView inventoryRoom(@PathVariable UUID id) { return catalog.inventoryRoom(id); }

    @PostMapping("/room-types") @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')") @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create a room type", description = "ADMIN only. Positive basePrice with at most two decimal places.")
    @ApiResponse(responseCode = "201", description = "Type created")
    public CatalogViews.TypeView createType(@Valid @RequestBody CatalogRequests.TypeCreate body,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return catalog.createType(body, jwt, ApiErrors.requestId(request));
    }
    @PatchMapping("/room-types/{id}") @PreAuthorize("hasRole('ADMIN')") @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Updated type with current version")
    @Operation(summary = "Edit or activate/deactivate a room type", description = "ADMIN only. Requires the current version from staff inventory.")
    public CatalogViews.TypeView patchType(@PathVariable UUID id, @Valid @RequestBody CatalogRequests.TypePatch body,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return catalog.patchType(id, body, jwt, ApiErrors.requestId(request));
    }
    @PostMapping("/rooms") @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')") @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create a room", description = "ADMIN only. Floor -5..200. Code is normalized to uppercase.")
    @ApiResponse(responseCode = "201", description = "Room created")
    public CatalogViews.RoomView createRoom(@Valid @RequestBody CatalogRequests.RoomCreate body,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return catalog.createRoom(body, jwt, ApiErrors.requestId(request));
    }
    @PatchMapping("/rooms/{id}") @PreAuthorize("hasRole('ADMIN')") @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Updated room with current version")
    @Operation(summary = "Edit or activate/deactivate a room", description = "ADMIN only. Current version required.")
    public CatalogViews.RoomView patchRoom(@PathVariable UUID id, @Valid @RequestBody CatalogRequests.RoomPatch body,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return catalog.patchRoom(id, body, jwt, ApiErrors.requestId(request));
    }
    @PatchMapping("/rooms/{id}/status") @PreAuthorize("hasAnyRole('ADMIN','EMPLEADO')") @SecurityRequirement(name = "bearerAuth")
    @ApiResponse(responseCode = "200", description = "Updated room with current version")
    @Operation(summary = "Change operational status", description = "ADMIN or EMPLEADO. Only version and operationalStatus are accepted.")
    public CatalogViews.RoomView patchStatus(@PathVariable UUID id, @Valid @RequestBody CatalogRequests.StatusPatch body,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return catalog.patchStatus(id, body, jwt, ApiErrors.requestId(request));
    }
}
