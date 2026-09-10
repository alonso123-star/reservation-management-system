package com.portfolio.reservation.reservations.application;

import com.portfolio.reservation.shared.api.PageView;
import java.util.*;
import org.springframework.data.domain.PageImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CustomerDirectory {
    public record Customer(UUID id, String name, String email) {}
    private final JdbcTemplate jdbc;
    public CustomerDirectory(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @PreAuthorize("hasAnyRole('EMPLEADO','ADMIN')")
    public PageView<Customer> search(String q, Integer page, Integer size) {
        var pageable = PageView.request(page, size, "name,asc", "name", Set.of("name"));
        String pattern = "%" + (q == null ? "" : q.strip().toLowerCase(Locale.ROOT)).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String where = " FROM users u JOIN roles r ON r.id=u.role_id WHERE u.active AND r.name='CLIENTE' AND (lower(u.name) LIKE ? ESCAPE '\\' OR u.email LIKE ? ESCAPE '\\')";
        long total = jdbc.queryForObject("SELECT count(*)" + where, Long.class, pattern, pattern);
        var items = jdbc.query("SELECT u.id,u.name,u.email" + where + " ORDER BY u.name,u.id LIMIT ? OFFSET ?",
                (rs, row) -> new Customer(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email")),
                pattern, pattern, pageable.getPageSize(), pageable.getOffset());
        return PageView.from(new PageImpl<>(items, pageable, total));
    }
}
