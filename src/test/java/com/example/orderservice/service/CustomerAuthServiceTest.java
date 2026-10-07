package com.example.orderservice.service;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.CustomerLoginResponse;
import com.example.orderservice.exception.CustomerAuthException;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.repository.CustomerAccountRepository;
import com.example.orderservice.repository.CustomerLoginCodeRepository;
import com.example.orderservice.repository.CustomerSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Storefront sign-in by emailed code, against the real (in-memory) database. MailService is mocked so each test
 * can read the code that "was emailed"; Clock is mocked so expiry can be tested without sleeping.
 */
@SpringBootTest
@ActiveProfiles("test")
class CustomerAuthServiceTest {

    private static final long PHNO = 9876543210L;
    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired
    private CustomerAuthService service;
    @Autowired
    private CustomerAccountRepository accounts;
    @Autowired
    private CustomerLoginCodeRepository codes;
    @Autowired
    private CustomerSessionRepository sessions;

    @MockitoBean
    private MailService mailService;
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
        codes.deleteAll();
        accounts.deleteAll();
        now = Instant.parse("2026-10-07T10:00:00Z");
        when(clock.instant()).thenAnswer(invocation -> now);
        when(mailService.send(anyString(), anyString(), anyString())).thenReturn(true);
    }

    // Requests a code and returns it, read back out of the mocked email body.
    private String requestAndCaptureCode(String email) {
        clearInvocations(mailService);
        service.requestCode(PHNO, email);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).send(anyString(), anyString(), body.capture());
        Matcher m = CODE.matcher(body.getValue());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void verifiedCodeIssuesASessionAndBindsTheEmail() {
        String code = requestAndCaptureCode("Alice@Example.com");

        CustomerLoginResponse login = service.verifyCode(PHNO, code);

        assertThat(service.resolveToken(login.token())).contains(PHNO);
        assertThat(service.boundEmail(PHNO)).contains("alice@example.com");
        assertThat(codes.findByPhno(PHNO)).isEmpty();
    }

    @Test
    void onceBoundCodesOnlyGoToTheBoundEmail() {
        service.verifyCode(PHNO, requestAndCaptureCode("alice@example.com"));
        now = now.plus(Duration.ofMinutes(2));

        clearInvocations(mailService);
        service.requestCode(PHNO, "attacker@example.com");

        verify(mailService).send(eq("alice@example.com"), anyString(), anyString());
    }

    @Test
    void wrongCodeIsRejectedAndAttemptsAreCapped() {
        String code = requestAndCaptureCode("alice@example.com");
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < CustomerAuthService.MAX_VERIFY_ATTEMPTS; i++) {
            assertThatThrownBy(() -> service.verifyCode(PHNO, wrong))
                    .isInstanceOf(CustomerAuthException.class).hasMessage("Invalid or expired code.");
        }
        // Even the right code no longer works once the guesses are used up.
        assertThatThrownBy(() -> service.verifyCode(PHNO, code))
                .isInstanceOf(CustomerAuthException.class).hasMessageContaining("Too many incorrect attempts");
    }

    @Test
    void expiredCodeIsRejected() {
        String code = requestAndCaptureCode("alice@example.com");
        now = now.plus(Duration.ofMinutes(11));

        assertThatThrownBy(() -> service.verifyCode(PHNO, code))
                .isInstanceOf(CustomerAuthException.class)
                .satisfies(e -> assertThat(((CustomerAuthException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void askingAgainWithinTheCooldownIsRejected() {
        requestAndCaptureCode("alice@example.com");
        now = now.plusSeconds(10);

        assertThatThrownBy(() -> service.requestCode(PHNO, "alice@example.com"))
                .isInstanceOf(CustomerAuthException.class)
                .satisfies(e -> assertThat(((CustomerAuthException) e).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void aNewCodeAfterTheCooldownReplacesTheOldOne() {
        String first = requestAndCaptureCode("alice@example.com");
        now = now.plusSeconds(61);
        String second = requestAndCaptureCode("alice@example.com");

        if (!first.equals(second)) {
            assertThatThrownBy(() -> service.verifyCode(PHNO, first)).isInstanceOf(CustomerAuthException.class);
        }
        assertThat(service.verifyCode(PHNO, second).token()).isNotBlank();
    }

    @Test
    void invalidEmailIsRejected() {
        assertThatThrownBy(() -> service.requestCode(PHNO, "not-an-email"))
                .isInstanceOf(CustomerAuthException.class)
                .satisfies(e -> assertThat(((CustomerAuthException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void logoutInvalidatesTheToken() {
        String token = service.issueSession(PHNO).token();
        service.logout(token);
        assertThat(service.resolveToken(token)).isEmpty();
    }

    @Test
    void expiredSessionNoLongerResolves() {
        String token = service.issueSession(PHNO).token();
        now = now.plus(Duration.ofDays(31));
        assertThat(service.resolveToken(token)).isEmpty();
    }

    @Test
    void adminRebindChangesTheEmailAndSignsOutExistingSessions() {
        service.verifyCode(PHNO, requestAndCaptureCode("squatter@example.com"));
        String oldToken = service.issueSession(PHNO).token();

        service.adminSetEmail(PHNO, "real.owner@example.com");

        assertThat(service.boundEmail(PHNO)).contains("real.owner@example.com");
        assertThat(service.resolveToken(oldToken)).isEmpty();
    }
}
