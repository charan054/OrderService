package com.example.orderservice.security;

import com.example.orderservice.entity.AdminRole;
import com.example.orderservice.entity.AuditLogEntry;
import com.example.orderservice.repository.AuditLogRepository;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditLogFilterTest {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @Mock
    private AuditLogRepository repository;

    private AuditLogFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AuditLogFilter(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void signInAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "x", null, List.of(new SimpleGrantedAuthority(role))));
    }

    private MockHttpServletResponse run(String method, String uri) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setQueryString("payerPin=1234");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void aServiceKeyWriteIsRecordedWithoutItsQueryString() throws Exception {
        signInAs("ROLE_SERVICE");

        run("POST", "/cart/42/ship");

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(repository).save(captor.capture());
        assertEquals("POST", captor.getValue().getMethod());
        assertEquals("/cart/42/ship", captor.getValue().getPath());
        assertEquals(200, captor.getValue().getStatus());
        assertEquals(NOW, captor.getValue().getTimestamp());
    }

    @Test
    void aNamedAdminWriteIsRecordedUnderTheirUsernameAndTheServiceKeyUnderItsOwnName() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AdminPrincipal("asha", AdminRole.MANAGER), null,
                List.of(new SimpleGrantedAuthority("ROLE_SERVICE"), new SimpleGrantedAuthority("ROLE_ADMIN"))));
        run("POST", "/cart/42/ship");
        signInAs("ROLE_SERVICE");
        run("POST", "/cart/43/ship");

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertEquals("asha", captor.getAllValues().get(0).getActor());
        assertEquals("service-key", captor.getAllValues().get(1).getActor());
    }

    @Test
    void readsCustomerSessionsAndAnonymousRequestsAreNotRecorded() throws Exception {
        signInAs("ROLE_SERVICE");
        run("GET", "/cart/all");
        signInAs("ROLE_CUSTOMER");
        run("POST", "/cart/checkout");
        SecurityContextHolder.clearContext();
        run("DELETE", "/cart/1");

        verify(repository, never()).save(any());
    }

    @Test
    void aFailingAuditWriteNeverBreaksTheRequest() throws Exception {
        signInAs("ROLE_SERVICE");
        doThrow(new IllegalStateException("db down")).when(repository).save(any(AuditLogEntry.class));

        assertEquals(200, run("PUT", "/coupons/add").getStatus());
    }

    @Test
    void anExceptionFromTheChainIsRecordedAsA500AndRethrown() {
        signInAs("ROLE_SERVICE");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/cart/9/deliver");

        assertThrows(ServletException.class, () -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> { throw new ServletException("boom"); }));

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(repository).save(captor.capture());
        assertEquals(500, captor.getValue().getStatus());
    }
}
