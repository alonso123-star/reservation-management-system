package com.portfolio.reservation.reporting;

import com.portfolio.reservation.shared.api.ApiException;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.Currency;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true) @PreAuthorize("hasRole('ADMIN')")
public class DashboardService {
    public record View(LocalDate from, LocalDate to, String hotelTimeZone, String currency, long nights,
            long reservationsCreated, long arrivals, long departures, long eligibleRooms,
            long roomNightsOccupied, long roomNightsAvailable, BigDecimal occupancyPercent,
            BigDecimal approvedPayments, BigDecimal refunds, BigDecimal netRevenue) {}
    private final JdbcTemplate jdbc;
    private final ZoneId zone;
    private final String currency;
    public DashboardService(JdbcTemplate jdbc,@Value("${hotel.time-zone:America/Lima}") String zone,@Value("${hotel.currency:PEN}") String currency) {
        this.jdbc=jdbc; this.zone=ZoneId.of(zone); this.currency=Currency.getInstance(currency).getCurrencyCode();
    }
    public View read(LocalDate from,LocalDate to) {
        long nights=ChronoUnit.DAYS.between(from,to);
        if(nights<1 || nights>366) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PERIOD","El periodo [from,to) debe contener entre 1 y 366 noches.");
        // One statement => one PostgreSQL snapshot for all metrics, with independent money aggregates (no fan-out joins).
        var result=jdbc.queryForMap("""
            WITH period AS (SELECT CAST(? AS date) lo, CAST(? AS date) hi, CAST(? AS timestamptz) start_at, CAST(? AS timestamptz) end_at),
            eligible AS (SELECT rm.id FROM rooms rm JOIN room_types rt ON rt.id=rm.room_type_id
                WHERE rm.active AND rt.active AND rm.operational_status='ACTIVE'),
            stays AS (SELECT r.* FROM reservations r WHERE r.status IN ('CONFIRMED','CHECKED_IN','CHECKED_OUT')),
            paid AS (SELECT COALESCE(sum(p.amount),0) amount,count(*) FILTER(WHERE p.currency<>?) other_currency
                FROM payments p,period d WHERE p.result='APPROVED' AND p.created_at>=d.start_at AND p.created_at<d.end_at),
            refunded AS (SELECT COALESCE(sum(r.amount),0) amount,count(*) FILTER(WHERE r.currency<>?) other_currency
                FROM refunds r,period d WHERE r.created_at>=d.start_at AND r.created_at<d.end_at)
            SELECT (SELECT count(*) FROM reservations r,period d WHERE r.created_at>=d.start_at AND r.created_at<d.end_at) created,
                (SELECT count(*) FROM stays r,period d WHERE r.check_in>=d.lo AND r.check_in<d.hi) arrivals,
                (SELECT count(*) FROM stays r,period d WHERE r.check_out>=d.lo AND r.check_out<d.hi) departures,
                (SELECT count(*) FROM eligible) rooms,
                (SELECT COALESCE(sum(LEAST(r.check_out,d.hi)-GREATEST(r.check_in,d.lo)),0)
                    FROM stays r JOIN eligible e ON e.id=r.room_id CROSS JOIN period d WHERE r.check_in<d.hi AND r.check_out>d.lo) occupied,
                paid.amount paid,refunded.amount refunded,paid.other_currency+refunded.other_currency other_currency FROM paid,refunded
            """,from,to,Timestamp.from(from.atStartOfDay(zone).toInstant()),Timestamp.from(to.atStartOfDay(zone).toInstant()),currency,currency);
        if(((Number)result.get("other_currency")).longValue()!=0)
            throw new ApiException(HttpStatus.CONFLICT,"METRIC_CURRENCY_MISMATCH","El periodo contiene otra moneda histórica; no se pueden sumar monedas distintas.");
        long rooms=((Number)result.get("rooms")).longValue(), occupied=((Number)result.get("occupied")).longValue(), capacity=rooms*nights;
        BigDecimal paid=((BigDecimal)result.get("paid")).setScale(2), refunds=((BigDecimal)result.get("refunded")).setScale(2);
        BigDecimal percent=capacity==0?null:BigDecimal.valueOf(occupied).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(capacity),2,RoundingMode.HALF_UP);
        return new View(from,to,zone.getId(),currency,nights,((Number)result.get("created")).longValue(),
                ((Number)result.get("arrivals")).longValue(),((Number)result.get("departures")).longValue(),rooms,occupied,capacity,percent,paid,refunds,paid.subtract(refunds));
    }
}
