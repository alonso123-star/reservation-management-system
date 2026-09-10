package com.portfolio.reservation.rooms.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Eligible public room. This estimate neither holds nor reserves the room; persisted reservations do not exist yet.")
public record AvailabilityView(UUID id, String code, int floor, CatalogViews.PublicType roomType,
        @Schema(description = "Calendar nights in [checkIn, checkOut)", example = "2") long nights,
        @Schema(description = "roomType.basePrice multiplied by nights, in roomType.currency; indicative, not a booking price", example = "251.00") BigDecimal estimatedTotal) {
}
