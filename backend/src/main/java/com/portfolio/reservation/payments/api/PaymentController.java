package com.portfolio.reservation.payments.api;

import com.portfolio.reservation.payments.application.PaymentService;
import com.portfolio.reservation.shared.api.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping(value = "/api/v1/reservations/{id}/payments", produces = "application/json")
@Tag(name = "Simulated payments", description = "Synthetic attempts and automatic full refunds; no card data or external provider")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid UUID, body, pagination or missing Idempotency-Key", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "401", description = "Authentication required or session invalid", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "403", description = "Role, Origin or CSRF denied", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "404", description = "Reservation missing or owned by another CLIENTE", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(responseCode = "409", description = "RESERVATION_ALREADY_PAID, RESERVATION_NOT_PAYABLE, IDEMPOTENCY_KEY_REUSED, IDEMPOTENCY_KEY_EXPIRED or REFUND_ALREADY_EXISTS", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
public class PaymentController {
    @Schema(description = "Empty JSON object. Amount, currency, actor, reference and result are determined exclusively by the server.")
    public record PayRequest() {}
    private final PaymentService payments;
    public PaymentController(PaymentService payments) { this.payments = payments; }
    @PostMapping
    @Operation(summary = "Create a simulated payment attempt", description = "Owner CLIENTE or EMPLEADO/ADMIN. Send {}. Only CONFIRMED without an approved payment is payable. First new attempt declines; next new attempt approves, using the historical full reservation amount. No cards or client-selected result. Same actor/key/reservation replays the original 201 snapshot for 24h, including DECLINED and snapshots before a later refund. New intent uses a new key. Origin and existing CSRF rules apply.")
    @ApiResponse(responseCode = "201", description = "Persisted APPROVED/DECLINED attempt or original receipt; Location points to payment history")
    public ResponseEntity<PaymentView> pay(@PathVariable UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject(value = "{}"))) @RequestBody PayRequest body,
            @Parameter(description = "UUID for one logical payment attempt, retained across retries", example = "123e4567-e89b-42d3-a456-426614174000")
            @RequestHeader("Idempotency-Key") UUID key, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return ResponseEntity.created(URI.create("/api/v1/reservations/" + id + "/payments"))
                .body(payments.pay(id, key, jwt, ApiErrors.requestId(request)));
    }
    @GetMapping
    @Operation(summary = "Read payment attempts and refunds", description = "Owner CLIENTE or EMPLEADO/ADMIN; foreign reservations return 404. Settlement UNPAID/PAID/REFUNDED is derived, independent of Reservation status. canPay is server policy; amountDue is zero when not payable. Stable pagination: page starts at 0, size 1-100 (20 default), sort createdAt or id with asc/desc. Each attempt includes its automatic full refund if present.")
    @ApiResponse(responseCode = "200", description = "Current settlement, amount due and paginated attempts with refunds")
    public PaymentHistory history(@PathVariable UUID id, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) String sort, @AuthenticationPrincipal Jwt jwt) {
        return payments.history(id, page, size, sort, jwt);
    }
}
