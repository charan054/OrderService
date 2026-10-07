package com.example.orderservice.security;

import com.example.orderservice.entity.AuditLogEntry;
import com.example.orderservice.repository.AuditLogRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/**
 * Records every write (POST/PUT/PATCH/DELETE) made with the service key into the audit log, after the request has
 * run so the response status is known. Sits after the authentication filters in SecurityConfig. Reads are not
 * logged, and neither are customer-session requests - this is the trail of admin/trusted-caller changes.
 * A failure to write the log entry is swallowed (logged): auditing must never turn a good request into an error.
 */
public class AuditLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AuditLogFilter.class);
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final AuditLogRepository repository;
    private final Clock clock;

    public AuditLogFilter(AuditLogRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean audited = WRITE_METHODS.contains(request.getMethod()) && isService();
        if (!audited) {
            chain.doFilter(request, response);
            return;
        }
        int status = 500;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            record(request, status);
        }
    }

    private void record(HttpServletRequest request, int status) {
        try {
            AuditLogEntry entry = new AuditLogEntry();
            entry.setTimestamp(Instant.now(clock));
            entry.setMethod(request.getMethod());
            String path = request.getRequestURI();
            entry.setPath(path.length() > 300 ? path.substring(0, 300) : path);
            entry.setStatus(status);
            entry.setRemoteAddr(request.getRemoteAddr());
            repository.save(entry);
        } catch (RuntimeException e) {
            log.error("Could not write audit log entry: {}", e.getMessage());
        }
    }

    private static boolean isService() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_SERVICE".equals(a.getAuthority()));
    }
}
