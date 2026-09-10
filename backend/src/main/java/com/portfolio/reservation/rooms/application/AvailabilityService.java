package com.portfolio.reservation.rooms.application;

import com.portfolio.reservation.rooms.api.*;
import com.portfolio.reservation.rooms.infrastructure.*;
import com.portfolio.reservation.shared.api.*;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AvailabilityService {
    private final RoomRepository rooms;
    private final String currency;

    public AvailabilityService(RoomRepository rooms, @Value("${hotel.currency:PEN}") String currency) {
        this.rooms = rooms;
        this.currency = Currency.getInstance(currency).getCurrencyCode();
    }

    public PageView<AvailabilityView> search(AvailabilityQuery filter) {
        if (!filter.checkIn().isBefore(filter.checkOut()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STAY", "La salida debe ser posterior a la entrada.");
        if (filter.minPrice() != null && filter.maxPrice() != null && filter.minPrice().compareTo(filter.maxPrice()) > 0)
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILTER", "El precio mínimo no puede superar el máximo.");
        long nights = ChronoUnit.DAYS.between(filter.checkIn(), filter.checkOut());
        var requested = PageView.request(filter.page(), filter.size(), filter.sort(), "code",
                Set.of("code", "floor", "basePrice", "capacity", "id"));
        var sort = Sort.by(requested.getSort().stream().map(order -> switch (order.getProperty()) {
            case "basePrice", "capacity" -> order.withProperty("roomType." + order.getProperty());
            default -> order;
        }).toList());
        var page = PageRequest.of(requested.getPageNumber(), requested.getPageSize(), sort);
        return PageView.from(rooms.findAll(AvailabilitySpecifications.matching(filter), page).map(room ->
                new AvailabilityView(room.getId(), room.getCode(), room.getFloor(),
                        CatalogViews.PublicType.from(room.getRoomType(), currency), nights,
                        room.getRoomType().getBasePrice().multiply(BigDecimal.valueOf(nights)))));
    }
}
