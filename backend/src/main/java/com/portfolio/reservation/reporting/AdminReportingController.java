package com.portfolio.reservation.reporting;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.portfolio.reservation.audit.AuditQueryService;
import com.portfolio.reservation.shared.api.PageView;
import java.time.LocalDate;
@RestController @PreAuthorize("hasRole('ADMIN')") @SecurityRequirement(name="bearerAuth")
@Tag(name="Administration", description="ADMIN exclusively. JWT/session, Origin and existing CSRF policy apply; no physical history deletion.")
@ApiResponses({
    @ApiResponse(responseCode="400",description="VALIDATION_ERROR, INVALID_REQUEST (including unknown role/fields), PASSWORD_POLICY, INVALID_PAGE or INVALID_PERIOD",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class))),
    @ApiResponse(responseCode="401",description="Authentication/session invalid",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class))),
    @ApiResponse(responseCode="403",description="ADMIN required or Origin/CSRF rejected",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class))),
    @ApiResponse(responseCode="404",description="USER_NOT_FOUND",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class))),
    @ApiResponse(responseCode="409",description="EMAIL_UNAVAILABLE, LAST_ACTIVE_ADMIN, USER_VERSION_CONFLICT or METRIC_CURRENCY_MISMATCH",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
})

@RequestMapping(value="/api/v1/admin",produces="application/json")
public class AdminReportingController {
    private final DashboardService dashboard;
    private final AuditQueryService audit;
    public AdminReportingController(DashboardService dashboard,AuditQueryService audit) { this.dashboard=dashboard; this.audit=audit; }
    @GetMapping("/dashboard")
    @Operation(summary="Read administrative metrics for hotel-local [from,to)",description="ADMIN. Required ISO dates, 1–366 nights. Created reservations use created_at; arrivals/departures use scheduled dates for CONFIRMED/CHECKED_IN/CHECKED_OUT. Occupancy = intersecting blocking room nights / (currently active, operational rooms with active type × nights). Not historical inventory or physical presence; zero denominator => null percent. APPROVED payments minus refunds each use their own created_at in hotel-local date boundaries, BigDecimal and configured currency; DECLINED excluded, mixed currency rejected. Example from=2030-10-01&to=2030-11-01.")
    @ApiResponse(responseCode="200",description="Metrics and explicit period, timezone and currency")
    public DashboardService.View dashboard(@RequestParam LocalDate from,@RequestParam LocalDate to) { return dashboard.read(from,to); }
    @GetMapping("/audit-events")
    @Operation(summary="Search safe audit events",description="ADMIN. Exact actorId/action/resource/resourceId/requestId, optional ISO instant [from,to). page 0–100000, size 1–100. Stable whitelisted sort. Historical metadata preserved; changes only oldRole/newRole/oldActive/newActive with validated scalar values; never raw credential JSON.")
    @ApiResponse(responseCode="200",description="Safe audit page")
    public PageView<AuditQueryService.Event> audit(@Valid @ParameterObject @ModelAttribute AuditQueryService.Filter filter) { return audit.list(filter); }
}
