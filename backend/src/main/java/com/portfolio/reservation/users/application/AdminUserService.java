package com.portfolio.reservation.users.application;

import com.portfolio.reservation.audit.AuditService;
import com.portfolio.reservation.identity.application.AuthService;
import com.portfolio.reservation.identity.infrastructure.SessionRepository;
import com.portfolio.reservation.users.api.*;
import com.portfolio.reservation.users.domain.*;
import com.portfolio.reservation.users.infrastructure.*;
import com.portfolio.reservation.shared.api.*;
import java.time.Clock;
import java.util.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true) @PreAuthorize("hasRole('ADMIN')")
public class AdminUserService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final SessionRepository sessions;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final Clock clock;
    public AdminUserService(UserRepository users, RoleRepository roles, SessionRepository sessions,
            JdbcTemplate jdbc, PasswordEncoder passwords, AuditService audit, Clock clock) {
        this.users=users; this.roles=roles; this.sessions=sessions; this.jdbc=jdbc;
        this.passwords=passwords; this.audit=audit; this.clock=clock;
    }
    public PageView<AdminUserView> list(AdminUserRequests.Filter f) {
        var page=PageView.request(f.page(),f.size(),f.sort(),"name",Set.of("name","email","active","createdAt","id"));
        Specification<User> spec=(r,q,b)-> {
            var terms=new ArrayList<jakarta.persistence.criteria.Predicate>();
            if(f.q()!=null && !f.q().isBlank()) {
                String term="%"+f.q().strip().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
                terms.add(b.or(b.like(b.lower(r.get("name")),term,'\\'),b.like(r.get("email"),term,'\\')));
            }
            if(f.role()!=null) terms.add(b.equal(r.get("role").get("name"),f.role()));
            if(f.active()!=null) terms.add(b.equal(r.get("active"),f.active()));
            return b.and(terms.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return PageView.from(users.findAll(spec,page).map(AdminUserView::from));
    }
    public AdminUserView detail(UUID id) { return AdminUserView.from(users.findById(id).orElseThrow(AdminUserService::missing)); }
    @Transactional
    public AdminUserView create(AdminUserRequests.Create body, Jwt jwt, UUID requestId) {
        AuthService.requirePassword(body.password());
        String hash=passwords.encode(body.password()); // Expensive hashing before acquiring database locks.
        lockAndAuthorize(jwt,null);
        String email=AuthService.normalizeEmail(body.email());
        if(users.findByEmail(email).isPresent()) throw error(HttpStatus.CONFLICT,"EMAIL_UNAVAILABLE","No se puede registrar ese correo.");
        var user=users.saveAndFlush(new User(body.name().strip(),email,hash,roles.findByName(body.role()).orElseThrow(),clock.instant()));
        audit.recordChanges(actor(jwt),"USER_CREATED","USER",user.getId(),requestId,
                Map.of("newRole",body.role().name(),"newActive",true));
        return AdminUserView.from(user);
    }
    @Transactional
    public AdminUserView role(UUID id, AdminUserRequests.ChangeRole body, Jwt jwt, UUID requestId) {
        var user=lockAndAuthorize(jwt,id); requireVersion(user,body.version());
        var old=user.getRole().getName();
        if(old==body.role()) return AdminUserView.from(user);
        if(user.isActive() && old==Role.Name.ADMIN && body.role()!=Role.Name.ADMIN) requireAnotherAdmin();
        user.changeRole(roles.findByName(body.role()).orElseThrow(),clock.instant());
        sessions.revokeAll(id,clock.instant()); users.flush();
        audit.recordChanges(actor(jwt),"USER_ROLE_CHANGED","USER",id,requestId,Map.of("oldRole",old.name(),"newRole",body.role().name()));
        return AdminUserView.from(user);
    }
    @Transactional
    public AdminUserView status(UUID id, AdminUserRequests.ChangeStatus body, Jwt jwt, UUID requestId) {
        var user=lockAndAuthorize(jwt,id); requireVersion(user,body.version());
        boolean old=user.isActive();
        if(old==body.active()) return AdminUserView.from(user);
        if(old && user.getRole().getName()==Role.Name.ADMIN && !body.active()) requireAnotherAdmin();
        user.changeActive(body.active(),clock.instant());
        sessions.revokeAll(id,clock.instant()); users.flush();
        audit.recordChanges(actor(jwt),body.active()?"USER_ACTIVATED":"USER_DEACTIVATED","USER",id,requestId,
                Map.of("oldActive",old,"newActive",body.active()));
        return AdminUserView.from(user);
    }
    private User lockAndAuthorize(Jwt jwt, UUID target) {
        // Global administrative gate, compatible with FK KEY SHARE locks from existing modules.
        jdbc.queryForObject("SELECT id FROM roles WHERE name='ADMIN' FOR NO KEY UPDATE",UUID.class);
        UUID actor=actor(jwt);
        var ids=new TreeSet<UUID>(); ids.add(actor); if(target!=null) ids.add(target);
        var locked=new HashMap<UUID,User>();
        for(UUID id:ids) users.lockById(id).ifPresent(u->locked.put(id,u));
        var current=locked.get(actor);
        var session=sessions.findById(UUID.fromString(jwt.getClaimAsString("sid"))).orElse(null);
        Number version=jwt.getClaim("ver");
        if(current==null || !current.isActive() || current.getRole().getName()!=Role.Name.ADMIN
                || current.getSecurityVersion()!=version.longValue() || session==null
                || !session.getUserId().equals(actor) || !session.isValid(clock.instant())
                || session.getSecurityVersion()!=current.getSecurityVersion())
            throw error(HttpStatus.UNAUTHORIZED,"INVALID_SESSION","La sesión no es válida. Inicia sesión de nuevo.");
        if(target!=null && !locked.containsKey(target)) throw missing();
        return target==null?current:locked.get(target);
    }
    private void requireAnotherAdmin() {
        Long count=jdbc.queryForObject("SELECT count(*) FROM users u JOIN roles r ON r.id=u.role_id WHERE u.active AND r.name='ADMIN'",Long.class);
        if(count<=1) throw error(HttpStatus.CONFLICT,"LAST_ACTIVE_ADMIN","Debe permanecer al menos un ADMIN activo.");
    }
    private static void requireVersion(User u,long version) {
        if(u.getVersion()!=version) throw error(HttpStatus.CONFLICT,"USER_VERSION_CONFLICT","El usuario cambió. Actualiza antes de continuar.");
    }
    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    private static ApiException missing() { return error(HttpStatus.NOT_FOUND,"USER_NOT_FOUND","Usuario no encontrado."); }
    private static ApiException error(HttpStatus status,String code,String detail) { return new ApiException(status,code,detail); }
}
