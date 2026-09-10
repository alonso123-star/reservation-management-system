package com.portfolio.reservation.rooms.application;

import com.portfolio.reservation.shared.api.ApiException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;

public final class StayRules {
    private StayRules() {}
    public static long nights(LocalDate checkIn, LocalDate checkOut) {
        if (checkIn == null || checkOut == null || !checkIn.isBefore(checkOut))
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STAY", "La salida debe ser posterior a la entrada.");
        return ChronoUnit.DAYS.between(checkIn, checkOut);
    }
}
