package com.portfolio.reservation.rooms.api;

import com.portfolio.reservation.rooms.application.AvailabilityService;
import com.portfolio.reservation.shared.api.PageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/rooms/availability", produces = "application/json")
@Tag(name = "Availability", description = "Public indicative search; no booking or room hold")
public class AvailabilityController {
    private final AvailabilityService availability;
    public AvailabilityController(AvailabilityService availability) { this.availability = availability; }

    @GetMapping
    @Operation(summary = "Search eligible rooms for a stay", description = "Anonymous access. Uses [checkIn,checkOut), capacity, catalog activity and operational status. Prices are nightly in hotel currency (PEN by default). No persisted reservations exist yet: dates calculate nights and estimated totals but do not exclude occupied rooms. This read never holds or reserves a room.")
    @ApiResponse(responseCode = "200", description = "Paginated eligible rooms with server-calculated estimate; empty items when no match")
    @ApiResponse(responseCode = "400", description = "Invalid or missing dates/guests, UUID, price range, pagination or sort",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class),
                    examples = @ExampleObject(value = "{\"type\":\"urn:reservation:error:invalid_stay\",\"title\":\"Bad Request\",\"status\":400,\"detail\":\"La salida debe ser posterior a la entrada.\",\"code\":\"INVALID_STAY\",\"requestId\":\"123e4567-e89b-12d3-a456-426614174000\"}")))
    public PageView<AvailabilityView> search(@Valid @ParameterObject @ModelAttribute AvailabilityQuery query) {
        return availability.search(query);
    }
}
