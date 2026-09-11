package com.portfolio.reservation.users.api;
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

import com.portfolio.reservation.users.application.AdminUserService;
import com.portfolio.reservation.shared.api.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
@RestController @PreAuthorize("hasRole('ADMIN')") @SecurityRequirement(name="bearerAuth")
@Tag(name="Administration", description="ADMIN exclusively. JWT/session, Origin and existing CSRF policy apply; no physical history deletion.")
@ApiResponses({
    @ApiResponse(responseCode="400",description="VALIDATION_ERROR, INVALID_REQUEST (including unknown role/fields), PASSWORD_POLICY, INVALID_PAGE or INVALID_PERIOD",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class))),
    @ApiResponse(responseCode="401",description="Authentication/session invalid",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class))),
    @ApiResponse(responseCode="403",description="ADMIN required or Origin/CSRF rejected",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class))),
    @ApiResponse(responseCode="404",description="USER_NOT_FOUND",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class))),
    @ApiResponse(responseCode="409",description="EMAIL_UNAVAILABLE, LAST_ACTIVE_ADMIN, USER_VERSION_CONFLICT or METRIC_CURRENCY_MISMATCH",content=@Content(mediaType="application/problem+json",schema=@Schema(implementation=ProblemDetail.class)))
})

@RequestMapping(value="/api/v1/users",produces="application/json")
public class AdminUserController {
    private final AdminUserService users;
    public AdminUserController(AdminUserService users) { this.users=users; }
    @GetMapping @Operation(summary="Search users",description="ADMIN. Server-side literal name/email substring, role/active filters and stable pagination.")
    @ApiResponse(responseCode="200",description="Safe user page")
    public PageView<AdminUserView> list(@Valid @ParameterObject @ModelAttribute AdminUserRequests.Filter filter) { return users.list(filter); }
    @GetMapping("/{id}") @Operation(summary="Read administrative user detail",description="ADMIN. No password hash, securityVersion or session material.")
    @ApiResponse(responseCode="200",description="Safe user detail")
    public AdminUserView detail(@PathVariable UUID id) { return users.detail(id); }
    @PostMapping @Operation(summary="Create user with explicit role",description="ADMIN. Example: name, email, password (12 characters / 72 UTF-8 bytes max), role=CLIENTE|EMPLEADO|ADMIN. Always active initially. Server-generated UUID and versions. Atomic USER_CREATED audit without credentials.")
    @ApiResponse(responseCode="201",description="Created safe user",content=@Content(schema=@Schema(implementation=AdminUserView.class)))
    public ResponseEntity<AdminUserView> create(@Valid @RequestBody AdminUserRequests.Create body,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request) {
        var value=users.create(body,jwt,ApiErrors.requestId(request));
        return ResponseEntity.created(URI.create("/api/v1/users/"+value.id())).body(value);
    }
    @PatchMapping("/{id}/role") @Operation(summary="Change user role",description="ADMIN. Send {\"role\":\"EMPLEADO\",\"version\":0}. Last active ADMIN cannot be demoted. Self-change allowed only with another active ADMIN; all target sessions revoked immediately. Unchanged role is a no-op.")
    @ApiResponse(responseCode="200",description="Updated safe user")
    public AdminUserView role(@PathVariable UUID id,@Valid @RequestBody AdminUserRequests.ChangeRole body,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request) { return users.role(id,body,jwt,ApiErrors.requestId(request)); }
    @PatchMapping("/{id}/status") @Operation(summary="Activate or deactivate user",description="ADMIN. Send {\"active\":false,\"version\":0}. Last active ADMIN protected under PostgreSQL lock. Self-deactivation invalidates own session. Reactivation never restores revoked sessions. Unchanged status is a no-op.")
    @ApiResponse(responseCode="200",description="Updated safe user")
    public AdminUserView status(@PathVariable UUID id,@Valid @RequestBody AdminUserRequests.ChangeStatus body,@AuthenticationPrincipal Jwt jwt,HttpServletRequest request) { return users.status(id,body,jwt,ApiErrors.requestId(request)); }
}
