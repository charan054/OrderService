package com.example.orderservice.service;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.AdminAccountView;
import com.example.orderservice.dto.AdminLoginResponse;
import com.example.orderservice.dto.NewAdminAccount;
import com.example.orderservice.entity.AdminAccount;
import com.example.orderservice.entity.AdminRole;
import com.example.orderservice.exception.AdminAuthException;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.AdminAccountRepository;
import com.example.orderservice.repository.AdminSessionRepository;
import com.example.orderservice.security.AdminPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** Named admin accounts against the real (in-memory) database; the clock is mocked so session expiry needs no sleeping. */
@SpringBootTest
@ActiveProfiles("test")
class AdminAuthServiceTest {
    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AdminAuthService service;
    @Autowired
    private AdminAccountRepository accounts;
    @Autowired
    private AdminSessionRepository sessions;

    @MockitoBean
    private Clock clock;
    @MockitoBean
    private ProductClient productClient;
    @MockitoBean
    private PhonepeClient phonepeClient;
    @MockitoBean
    private OrderKafkaProducer orderKafkaProducer;

    private Instant now;

    @BeforeEach
    void reset() {
        sessions.deleteAll();
        accounts.deleteAll();
        now = Instant.parse("2026-10-08T10:00:00Z");
        when(clock.instant()).thenAnswer(invocation -> now);
    }

    private AdminAccountView create(String username, AdminRole role) {
        return service.create(new NewAdminAccount(username, PASSWORD, role), "service-key");
    }

    private void assertRejected(Runnable action, HttpStatus status) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AdminAuthException.class)
                .extracting(e -> ((AdminAuthException) e).getStatus()).isEqualTo(status);
    }

    @Test
    void signingInGivesATokenThatResolvesToTheAccountAndRole() {
        create("Asha", AdminRole.MANAGER);

        AdminLoginResponse login = service.login(" ASHA ", PASSWORD);

        assertThat(login.username()).isEqualTo("asha");
        assertThat(login.role()).isEqualTo(AdminRole.MANAGER);
        assertThat(service.resolveToken(login.token())).contains(new AdminPrincipal("asha", AdminRole.MANAGER));
        assertThat(accounts.findByUsername("asha").orElseThrow().getLastLoginAt()).isEqualTo(now);
    }

    @Test
    void thePasswordAndTokenAreNeverStoredInThePlain() {
        create("asha", AdminRole.SUPPORT);
        AdminLoginResponse login = service.login("asha", PASSWORD);

        assertThat(accounts.findByUsername("asha").orElseThrow().getPasswordHash()).startsWith("$2").doesNotContain(PASSWORD);
        assertThat(sessions.findAll()).singleElement()
                .satisfies(s -> assertThat(s.getTokenHash()).hasSize(64).isNotEqualTo(login.token()));
    }

    @Test
    void wrongPasswordUnknownUserAndDisabledAccountAllGetTheSame401() {
        create("asha", AdminRole.SUPPORT);
        create("gone", AdminRole.SUPPORT);
        service.setActive("gone", false);

        for (String[] attempt : new String[][]{{"asha", "wrong password!"}, {"nobody", PASSWORD}, {"gone", PASSWORD}, {"asha", null}}) {
            assertThatThrownBy(() -> service.login(attempt[0], attempt[1]))
                    .isInstanceOf(AdminAuthException.class)
                    .hasMessage("Invalid username or password.")
                    .extracting(e -> ((AdminAuthException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void anOverlongPasswordIsRejectedWithoutError() {
        create("asha", AdminRole.SUPPORT);

        assertRejected(() -> service.login("asha", PASSWORD + "x".repeat(80)), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aSessionExpiresAfterItsLifetime() {
        create("asha", AdminRole.SUPPORT);
        AdminLoginResponse login = service.login("asha", PASSWORD);

        now = now.plus(Duration.ofHours(11));
        assertThat(service.resolveToken(login.token())).isPresent();
        now = now.plus(Duration.ofHours(2));
        assertThat(service.resolveToken(login.token())).isEmpty();
        assertThat(sessions.findAll()).isEmpty();
    }

    @Test
    void logoutEndsOnlyThatSession() {
        create("asha", AdminRole.SUPPORT);
        AdminLoginResponse first = service.login("asha", PASSWORD);
        AdminLoginResponse second = service.login("asha", PASSWORD);

        service.logout(first.token());

        assertThat(service.resolveToken(first.token())).isEmpty();
        assertThat(service.resolveToken(second.token())).isPresent();
        service.logout(null);
        service.logout("never-issued");
    }

    @Test
    void aRoleChangeTakesEffectOnTheExistingSession() {
        create("boss", AdminRole.OWNER);
        create("asha", AdminRole.MANAGER);
        AdminLoginResponse login = service.login("asha", PASSWORD);

        service.setRole("asha", AdminRole.SUPPORT);

        assertThat(service.resolveToken(login.token())).contains(new AdminPrincipal("asha", AdminRole.SUPPORT));
    }

    @Test
    void disablingEndsSessionsAndBlocksSignInUntilReEnabled() {
        create("asha", AdminRole.MANAGER);
        AdminLoginResponse login = service.login("asha", PASSWORD);

        service.setActive("asha", false);
        assertThat(service.resolveToken(login.token())).isEmpty();
        assertRejected(() -> service.login("asha", PASSWORD), HttpStatus.UNAUTHORIZED);

        service.setActive("asha", true);
        assertThat(service.login("asha", PASSWORD).token()).isNotBlank();
    }

    @Test
    void theLastActiveOwnerCannotBeDemotedOrDisabled() {
        create("boss", AdminRole.OWNER);

        assertRejected(() -> service.setRole("boss", AdminRole.MANAGER), HttpStatus.CONFLICT);
        assertRejected(() -> service.setActive("boss", false), HttpStatus.CONFLICT);

        create("boss2", AdminRole.OWNER);
        service.setRole("boss", AdminRole.MANAGER);
        assertRejected(() -> service.setActive("boss2", false), HttpStatus.CONFLICT);
        assertThat(accounts.findByUsername("boss2").orElseThrow().isActive()).isTrue();
    }

    @Test
    void aDisabledOwnerDoesNotCountAsAnotherOwner() {
        create("boss", AdminRole.OWNER);
        create("boss2", AdminRole.OWNER);
        service.setActive("boss2", false);

        assertRejected(() -> service.setRole("boss", AdminRole.SUPPORT), HttpStatus.CONFLICT);
    }

    @Test
    void usernamesAreValidatedAndUnique() {
        create("asha", AdminRole.SUPPORT);

        assertRejected(() -> create("ASHA", AdminRole.SUPPORT), HttpStatus.CONFLICT);
        for (String bad : new String[]{"ab", "has space", "-leading", "x".repeat(33), "semi;colon", "", null}) {
            assertRejected(() -> create(bad, AdminRole.SUPPORT), HttpStatus.BAD_REQUEST);
        }
        assertRejected(() -> service.create(new NewAdminAccount("newperson", PASSWORD, null), "service-key"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void weakOrOverlongPasswordsAreRefusedOnCreateAndReset() {
        for (String bad : new String[]{null, "short", "         ", "x".repeat(73)}) {
            assertRejected(() -> service.create(new NewAdminAccount("newperson", bad, AdminRole.SUPPORT), "service-key"),
                    HttpStatus.BAD_REQUEST);
        }
        create("asha", AdminRole.SUPPORT);
        assertRejected(() -> service.resetPassword("asha", "short"), HttpStatus.BAD_REQUEST);
        assertThat(accounts.findAll()).extracting(AdminAccount::getUsername).containsExactly("asha");
    }

    @Test
    void resettingAPasswordEndsTheirSessionsAndTheOldPasswordStopsWorking() {
        create("asha", AdminRole.SUPPORT);
        AdminLoginResponse login = service.login("asha", PASSWORD);

        service.resetPassword("asha", "a brand new passphrase");

        assertThat(service.resolveToken(login.token())).isEmpty();
        assertRejected(() -> service.login("asha", PASSWORD), HttpStatus.UNAUTHORIZED);
        assertThat(service.login("asha", "a brand new passphrase").token()).isNotBlank();
    }

    @Test
    void changingYourOwnPasswordNeedsTheCurrentOne() {
        create("asha", AdminRole.SUPPORT);

        assertRejected(() -> service.changeOwnPassword("asha", "not my password", "a brand new passphrase"), HttpStatus.FORBIDDEN);
        assertRejected(() -> service.changeOwnPassword("asha", null, "a brand new passphrase"), HttpStatus.FORBIDDEN);
        service.changeOwnPassword("asha", PASSWORD, "a brand new passphrase");

        assertThat(service.login("asha", "a brand new passphrase").token()).isNotBlank();
    }

    @Test
    void theListNeverCarriesAPasswordHashAndIsSortedByName() {
        create("zoe", AdminRole.SUPPORT);
        create("asha", AdminRole.OWNER);

        assertThat(service.list()).extracting(AdminAccountView::username).containsExactly("asha", "zoe");
        assertThat(service.list().toString()).doesNotContain("$2");
        assertThat(service.list().get(0).createdBy()).isEqualTo("service-key");
    }

    @Test
    void unknownAccountsAre404() {
        assertRejected(() -> service.setRole("nobody", AdminRole.SUPPORT), HttpStatus.NOT_FOUND);
        assertRejected(() -> service.setActive("nobody", false), HttpStatus.NOT_FOUND);
        assertRejected(() -> service.resetPassword("nobody", "a brand new passphrase"), HttpStatus.NOT_FOUND);
    }
}
