package com.portfolio.reservation.shared.api;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);
    private final ObjectMapper mapper;

    public ApiErrors(ObjectMapper mapper) { this.mapper = mapper; }

    public ProblemDetail problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:reservation:error:" + code.toLowerCase(Locale.ROOT)));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("requestId", requestId(request));
        return problem;
    }

    public static UUID requestId(HttpServletRequest request) {
        Object value = request.getAttribute("requestId");
        if (value instanceof UUID id) return id;
        UUID id = UUID.randomUUID();
        request.setAttribute("requestId", id);
        return id;
    }

    public void write(HttpServletRequest request, HttpServletResponse response,
                      HttpStatus status, String code, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem(status, code, detail, request));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> business(ApiException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status())
                .body(problem(exception.status(), exception.code(), exception.getMessage(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        var body = problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Revisa los campos del formulario.", request);
        var fields = new LinkedHashMap<String, String>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), Objects.toString(error.getDefaultMessage(), "Valor inválido")));
        body.setProperty("errors", fields);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> invalidInput(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "La petición contiene campos desconocidos o valores inválidos.", request));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> conflict(DataIntegrityViolationException exception, HttpServletRequest request) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint) {
                String name = Objects.toString(constraint.getConstraintName(), "");
                if (name.equals("users_email_key"))
                    return ResponseEntity.status(409).body(problem(HttpStatus.CONFLICT, "EMAIL_UNAVAILABLE",
                            "No se puede registrar ese correo.", request));
                if (name.equals("uq_payments_approved_reservation"))
                    return ResponseEntity.status(409).body(problem(HttpStatus.CONFLICT, "RESERVATION_ALREADY_PAID",
                            "La reserva ya tiene un pago aprobado.", request));
                if (name.equals("uq_refunds_payment"))
                    return ResponseEntity.status(409).body(problem(HttpStatus.CONFLICT, "REFUND_ALREADY_EXISTS",
                            "El pago ya tiene un reembolso completo.", request));
                if (name.equals("ex_reservations_room_stay"))
                    return ResponseEntity.status(409).body(problem(HttpStatus.CONFLICT, "ROOM_NOT_AVAILABLE",
                            "La habitación ya no está disponible para esas fechas. Actualiza la búsqueda.", request));
                if (name.equals("uq_room_types_name") || name.equals("uq_rooms_code"))
                    return ResponseEntity.status(409).body(problem(HttpStatus.CONFLICT,
                            name.equals("uq_room_types_name") ? "TYPE_NAME_TAKEN" : "ROOM_CODE_TAKEN",
                            "El nombre del tipo o código de habitación ya existe.", request));
            }
        }
        return ResponseEntity.status(409).body(problem(HttpStatus.CONFLICT, "DATA_CONFLICT",
                "No se pudo completar la operación por un conflicto de datos.", request));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(HttpServletRequest request) {
        return ResponseEntity.status(403).body(problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "No tienes permiso para realizar esta operación.", request));
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> staleVersion(HttpServletRequest request) {
        return ResponseEntity.status(409).body(problem(HttpStatus.CONFLICT, "STALE_VERSION",
                "Otro usuario modificó este registro. Recarga antes de guardar.", request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof org.springframework.web.ErrorResponse error) {
            var status = HttpStatus.valueOf(error.getStatusCode().value());
            return ResponseEntity.status(status).body(problem(status, "REQUEST_ERROR", status.getReasonPhrase(), request));
        }
        // Never log request bodies, credentials, tokens or exception messages from SQL.
        LOG.error("Unexpected error type={} requestId={}", exception.getClass().getSimpleName(), requestId(request));
        return ResponseEntity.internalServerError().body(problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", "No se pudo completar la operación.", request));
    }
}
