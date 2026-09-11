package com.portfolio.reservation.reservations.api;

import com.portfolio.reservation.reservations.application.*;
import com.portfolio.reservation.shared.api.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1", produces = "application/json")
@Tag(name = "Reservations", description = "Creation, history and cancellation; no payment or reception operations")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid dates, guests, request, amount, cancellation policy or pagination", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "401", description = "Authentication required or session invalid", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "403", description = "Role, Origin or CSRF denied", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "404", description = "Missing room/customer/reservation or reservation owned by another client", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "409", description = "ROOM_NOT_AVAILABLE, IDEMPOTENCY_KEY_REUSED, IDEMPOTENCY_KEY_EXPIRED, INVALID_RESERVATION_STATE, STALE_VERSION", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
public class ReservationController {
    private final ReservationService reservations;
    private final CustomerDirectory customers;
    public ReservationController(ReservationService reservations, CustomerDirectory customers) {
        this.reservations = reservations; this.customers = customers;
    }
    @PostMapping("/reservations") @PreAuthorize("hasRole('CLIENTE')")
    @Operation(summary = "Create own confirmed reservation", description = "CLIENTE only. Ownership, actor, status and price come from the server. Requires allowed Origin and the existing CSRF policy. Same key/request replays the original 201 snapshot for 24 hours, even if the reservation later changes; consult detail for current state. Keys are never reused after expiry. Overlap produces 409 ROOM_NOT_AVAILABLE.")
    @ApiResponse(responseCode = "201", description = "Created reservation, or original creation receipt; Location points to its detail")
    public ResponseEntity<ReservationView> create(@Valid @RequestBody ReservationRequests.Create body,
            @Parameter(description = "Required UUID per logical creation; retain for retries", example = "123e4567-e89b-42d3-a456-426614174000")
            @RequestHeader("Idempotency-Key") UUID key, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return created(reservations.create(body, key, jwt, ApiErrors.requestId(request)));
    }
    @PostMapping("/staff/reservations") @PreAuthorize("hasAnyRole('EMPLEADO','ADMIN')")
    @Operation(summary = "Create for an existing active CLIENTE", description = "EMPLEADO or ADMIN. createdBy is the authenticated actor. Same idempotency policy as client creation, scoped to staff operation.")
    @ApiResponse(responseCode = "201", description = "Created reservation or original receipt")
    public ResponseEntity<ReservationView> staffCreate(@Valid @RequestBody ReservationRequests.StaffCreate body,
            @Parameter(description = "Required UUID; same logical request reuses it", example = "123e4567-e89b-42d3-a456-426614174000")
            @RequestHeader("Idempotency-Key") UUID key, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return created(reservations.staffCreate(body, key, jwt, ApiErrors.requestId(request)));
    }
    @GetMapping("/reservations")
    @Operation(summary = "Search reservation history", description = "CLIENTE sees only own reservations. EMPLEADO/ADMIN see all and may filter customerId/roomId. Date bounds filter arrival inclusively; code is an exact case-insensitive match.")
    @ApiResponse(responseCode = "200", description = "Page of authorized reservation views")
    public PageView<ReservationView> history(@Valid @ParameterObject @ModelAttribute ReservationFilter filter, @AuthenticationPrincipal Jwt jwt) {
        return reservations.history(filter, jwt);
    }
    @GetMapping("/reservations/{id}")
    @Operation(summary = "Read reservation detail", description = "Owner or EMPLEADO/ADMIN. An unrelated CLIENTE receives 404.")
    @ApiResponse(responseCode = "200", description = "Current reservation including historical price and canCancel")
    public ReservationView detail(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) { return reservations.detail(id, jwt); }

    @PostMapping("/reservations/{id}/cancel")
    @Operation(summary = "Cancel a confirmed reservation", description = "Owner before arrival day in hotel.time-zone (America/Lima default), or EMPLEADO/ADMIN before operational check-in. Reason and current version are required. Locks the same reservation as payment, creates one automatic full simulated refund when an approved payment exists, and cancels atomically. Preserves history, releases dates and records actor. No independent refund endpoint.")
    @ApiResponse(responseCode = "200", description = "Cancelled reservation with updated version")
    public ReservationView cancel(@PathVariable UUID id, @Valid @RequestBody ReservationRequests.Cancel body,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return reservations.cancel(id, body, jwt, ApiErrors.requestId(request));
    }
    @GetMapping("/staff/customers") @PreAuthorize("hasAnyRole('EMPLEADO','ADMIN')")
    @Operation(summary = "Find active clients for staff booking", description = "Minimal id/name/email directory; q searches name/email, max 100 characters, stable name/id order. No user administration.")
    @ApiResponse(responseCode = "200", description = "Page of active clients")
    public PageView<CustomerDirectory.Customer> customers(@RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        if (q != null && q.length() > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILTER", "La búsqueda admite hasta 100 caracteres.");
        return customers.search(q, page, size);
    }
    private ResponseEntity<ReservationView> created(ReservationView response) {
        return ResponseEntity.created(URI.create("/api/v1/reservations/" + response.id())).body(response);
    }
}
