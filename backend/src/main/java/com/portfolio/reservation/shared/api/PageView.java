package com.portfolio.reservation.shared.api;

import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;

public record PageView<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
    public static <T> PageView<T> from(Page<T> value) {
        return new PageView<>(value.getContent(), value.getNumber(), value.getSize(), value.getTotalElements(), value.getTotalPages());
    }
    public static Pageable request(Integer page, Integer size, String sort, String defaultField, Set<String> allowed) {
        int p = page == null ? 0 : page;
        int s = size == null ? 20 : size;
        if (p < 0 || p > 100000 || s < 1 || s > 100) throw invalid("Página inválida; size debe estar entre 1 y 100.");
        String[] parts = (sort == null ? defaultField + ",asc" : sort).split(",", -1);
        if (parts.length != 2 || !allowed.contains(parts[0]) || !Set.of("asc", "desc").contains(parts[1]))
            throw invalid("Orden no permitido. Usa campo,asc o campo,desc.");
        var ordering = Sort.by(Sort.Direction.fromString(parts[1]), parts[0]);
        if (!parts[0].equals("id")) ordering = ordering.and(Sort.by("id"));
        return PageRequest.of(p, s, ordering);
    }
    private static ApiException invalid(String detail) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", detail); }
}
