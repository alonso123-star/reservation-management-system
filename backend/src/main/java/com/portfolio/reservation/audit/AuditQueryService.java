package com.portfolio.reservation.audit;

import com.portfolio.reservation.shared.api.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service @Transactional(readOnly=true) @PreAuthorize("hasRole('ADMIN')")
public class AuditQueryService {
    public record Filter(UUID actorId, @Size(max=50) String action, @Size(max=30) String resource,
            UUID resourceId, Instant from, Instant to, UUID requestId, Integer page, Integer size,
            @Schema(description="occurredAt|action|resource|id,asc|desc; default occurredAt,asc; UUID tie-break") String sort) {}
    public record Event(UUID id, UUID actorId, String action, String resource, UUID resourceId,
            Map<String,Object> changes, Instant occurredAt, UUID requestId) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public AuditQueryService(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public PageView<Event> list(Filter f) {
        if(f.from()!=null && f.to()!=null && !f.from().isBefore(f.to()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PERIOD","from debe ser anterior a to.");
        var page=PageView.request(f.page(),f.size(),f.sort(),"occurredAt",Set.of("occurredAt","action","resource","id"));
        var where=new StringBuilder(" WHERE 1=1"); var args=new ArrayList<Object>();
        add(where,args,"actor_id = ?",f.actorId()); add(where,args,"action = ?",f.action());
        add(where,args,"resource_type = ?",f.resource()); add(where,args,"resource_id = ?",f.resourceId());
        add(where,args,"request_id = ?",f.requestId());
        add(where,args,"occurred_at >= ?",f.from()==null?null:java.sql.Timestamp.from(f.from()));
        add(where,args,"occurred_at < ?",f.to()==null?null:java.sql.Timestamp.from(f.to()));
        long total=jdbc.queryForObject("SELECT count(*) FROM audit_events"+where,Long.class,args.toArray());
        var names=Map.of("occurredAt","occurred_at","action","action","resource","resource_type","id","id");
        String order=page.getSort().stream().map(o->names.get(o.getProperty())+" "+o.getDirection().name()).collect(java.util.stream.Collectors.joining(","));
        args.add(page.getPageSize()); args.add(page.getOffset());
        var rows=jdbc.query("SELECT id,actor_id,action,resource_type,resource_id,changes,occurred_at,request_id FROM audit_events"+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",
                (r,n)->new Event(r.getObject("id",UUID.class),r.getObject("actor_id",UUID.class),r.getString("action"),r.getString("resource_type"),
                        r.getObject("resource_id",UUID.class),AuditChanges.sanitize(json.readTree(r.getString("changes"))),r.getTimestamp("occurred_at").toInstant(),r.getObject("request_id",UUID.class)),args.toArray());
        return new PageView<>(rows,page.getPageNumber(),page.getPageSize(),total,(int)((total+page.getPageSize()-1)/page.getPageSize()));
    }
    private static void add(StringBuilder sql,List<Object> args,String term,Object value) { if(value!=null) { sql.append(" AND ").append(term); args.add(value); } }
}
