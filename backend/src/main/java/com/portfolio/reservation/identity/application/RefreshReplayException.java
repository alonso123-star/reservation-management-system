package com.portfolio.reservation.identity.application;

import com.portfolio.reservation.shared.api.ApiException;
import org.springframework.http.HttpStatus;

public class RefreshReplayException extends ApiException {
    public RefreshReplayException() {
        super(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED", "La sesión ha sido revocada. Inicia sesión de nuevo.");
    }
}
