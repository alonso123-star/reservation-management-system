package com.portfolio.reservation.reception.api;

import com.portfolio.reservation.reception.application.ReceptionService;
import com.portfolio.reservation.reservations.api.ReservationView;
import com.portfolio.reservation.shared.api.ApiErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping(value = "/api/v1/reservations", produces = "application/json")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Reception", description = "EMPLEADO/ADMIN only. Current version required; duplicates return 409 without repeating timestamps or audit. Same reservation lock as payment/cancel. No Idempotency-Key required. Allowed Origin and existing CSRF policy apply.")
@ApiResponses({
    @ApiResponse(responseCode = "200", description = "Updated Reservation, timestamps, version and staff reception capabilities", content = @Content(schema = @Schema(implementation = ReservationView.class))),
    @ApiResponse(responseCode = "400", description = "Invalid UUID/body/version or unknown fields", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "401", description = "Authentication required or session invalid", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "403", description = "CLIENTE, Origin or CSRF denied", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "404", description = "Reservation missing", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "409", description = "STALE_VERSION, INVALID_RESERVATION_STATE, RESERVATION_NOT_FULLY_PAID, CHECK_IN_OUTSIDE_STAY, ARRIVAL_DEADLINE_NOT_PASSED or INVALID_RECEPTION_TIMESTAMP", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
public class ReceptionController {
    public record Request(@NotNull @PositiveOrZero @Schema(description = "Current Reservation version; no status, actor or timestamps accepted", example = "0") Long version) {}
    private final ReceptionService reception;
    public ReceptionController(ReceptionService reception) { this.reception = reception; }
    @PostMapping("/{id}/check-in")
    @Operation(summary = "Check in a paid guest", description = "CONFIRMED -> CHECKED_IN. EMPLEADO/ADMIN. Approved full payment without refund required. Hotel-local date must satisfy checkIn <= today < checkOut. Sets checkedInAt once and audits atomically. Send {\"version\":0}; invalid state or lost race returns 409.")
    public ReservationView checkIn(@PathVariable UUID id, @Valid @RequestBody Request body, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return reception.transition(id, body.version(), ReceptionService.Action.CHECK_IN, jwt, ApiErrors.requestId(request));
    }
    @PostMapping("/{id}/check-out")
    @Operation(summary = "Check out an admitted guest", description = "CHECKED_IN -> CHECKED_OUT. EMPLEADO/ADMIN. Sets checkedOutAt once. Early departure preserves original dates, range, price and blocking nights; no new charge or refund. Send {\"version\":1}.")
    public ReservationView checkOut(@PathVariable UUID id, @Valid @RequestBody Request body, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return reception.transition(id, body.version(), ReceptionService.Action.CHECK_OUT, jwt, ApiErrors.requestId(request));
    }
    @PostMapping("/{id}/no-show")
    @Operation(summary = "Record a guest who did not arrive", description = "CONFIRMED -> NO_SHOW. EMPLEADO/ADMIN. Strictly after hotel.reception.arrival-deadline on the arrival date (22:00 default in hotel.time-zone, America/Lima default). At the exact deadline still rejected. Releases blocking dates, preserves payment history without refund, uses updatedAt and audit without inventing checkout timestamps. Send {\"version\":0}.")
    public ReservationView noShow(@PathVariable UUID id, @Valid @RequestBody Request body, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return reception.transition(id, body.version(), ReceptionService.Action.NO_SHOW, jwt, ApiErrors.requestId(request));
    }
}
