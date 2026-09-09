package com.portfolio.reservation.shared.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System", description = "Technical endpoints for the executable foundation")
public class SystemController {

    @GetMapping("/info")
    @Operation(summary = "Read application identity and implementation phase")
    public SystemInfo info() {
        return new SystemInfo("Reservation Management System", "0.3.0", 3);
    }

    public record SystemInfo(String name, String version, int phase) {
    }
}
